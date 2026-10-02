package com.yjjoker.learningagent.harness.review;

import com.yjjoker.learningagent.client.AliyunLlmClient;
import com.yjjoker.learningagent.config.AliyunLlmProperties;
import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalProgress;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalStepProgress;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

// 显式启用才产生真实模型费用；输入使用合成样本，不连接业务数据库或执行写工具。
@EnabledIfEnvironmentVariable(named = "ANSWER_REVIEW_ALIYUN_TEST", matches = "true")
@Slf4j
class AnswerReviewAliyunTest {

    // 用户只要求讲解，模型却凭自己的讲解宣布通过；只能改口，不能补写进度。
    @Test
    void rejectsOriginalCollectionHallucination() {
        assertEquals(AnswerReviewResult.Action.REWRITE, review(new AgentRunContext(),
                "给我讲解一下java当中的集合", "Collection和Map是两大体系。你已经掌握，第一步完成条件已满足，我们正式进入第二步。",
                AgentTaskStepStatus.PENDING).getAction());
    }

    // 已有数据库完成记录时允许如实说明，不要求本轮重复调用完成工具。
    @Test
    void acceptsPreviouslyCompletedStepWithoutNewTool() {
        assertEquals(AnswerReviewResult.Action.PASS, review(new AgentRunContext(),
                "查询第一步的状态", "数据库记录第一步已完成。这不代表独立验证过掌握程度。",
                AgentTaskStepStatus.COMPLETED).getAction());
    }

    // 明确的操作要求被纯文字冒充执行时，应回原循环补行动，而不是只换个成功说法。
    @Test
    void detectsMissingRequestedAction() {
        assertEquals(AnswerReviewResult.Action.CONTINUE, review(new AgentRunContext(),
                "请提交申请，将第一步更新为进行中，我会在审批卡确认。", "已经把第一步更新为进行中。",
                AgentTaskStepStatus.PENDING).getAction());
    }

    // 已拒绝的调用不能被审查建议重新执行；错误成功声明应改写为真实拒绝结果。
    @Test
    void refusesToBypassRejectedApproval() {
        AgentRunContext context = new AgentRunContext();
        ToolCall call = new ToolCall("test-call", "update_task_progress", "{\"status\":\"COMPLETED\"}");
        context.requestToolExecution(call);
        context.recordToolApproval(call, "REJECTED");
        context.rejectToolExecution(call, ToolExecutionResult.failure("APPROVAL_REJECTED", "用户拒绝了这次工具调用", false));
        assertEquals(AnswerReviewResult.Action.REWRITE, review(context,
                "请申请完成第一步", "第一步已经更新为完成。", AgentTaskStepStatus.IN_PROGRESS).getAction());
    }

    // 原来的参数失败不否定后来的真实成功；版本从请求 v1 变为结果 v2 也不是写入失败。
    @Test
    void acceptsCorrectedAndApprovedCallDespiteEarlierValidationFailure() {
        AgentRunContext context = new AgentRunContext();
        ToolCall invalid = new ToolCall("invalid-call", "update_task_progress", "{\"completionBasis\":\"USER_CONFIRMED\"}");
        context.requestToolExecution(invalid);
        context.failToolValidation(invalid, ToolExecutionResult.failure("INVALID_STEP_PROGRESS", "进行中应使用 NOT_APPLICABLE", true));
        ToolCall corrected = new ToolCall("corrected-call", "update_task_progress",
                "{\"updates\":[{\"stepRef\":\"goal-1-v1-step-1\",\"status\":\"IN_PROGRESS\",\"completionBasis\":\"NOT_APPLICABLE\"}]}");
        context.requestToolExecution(corrected);
        context.recordToolApproval(corrected, "APPROVED");
        context.startToolExecution(corrected);
        context.completeToolExecution(corrected, ToolExecutionResult.success(
                "{\"planVersion\":2,\"steps\":[{\"stepRef\":\"goal-1-v2-step-1\",\"status\":\"IN_PROGRESS\"}]}"));
        assertEquals(AnswerReviewResult.Action.PASS, review(context,
                "请申请第一步进行中，我会在审批卡确认。", "你已批准申请，第一步已更新为进行中，未判定掌握。",
                AgentTaskStepStatus.IN_PROGRESS).getAction());
    }

    // 用户原话要求完成，但之后已经拒绝；如实说明拒绝应通过，而不是要求再申请。
    @Test
    void acceptsTruthfulRejectionWithoutRequestingAnotherApproval() {
        AgentRunContext context = new AgentRunContext();
        ToolCall call = new ToolCall("rejected-call", "update_task_progress", "{\"status\":\"COMPLETED\"}");
        context.requestToolExecution(call);
        context.recordToolApproval(call, "REJECTED");
        context.rejectToolExecution(call, ToolExecutionResult.failure("APPROVAL_REJECTED", "用户拒绝本次完成申请", false));
        assertEquals(AnswerReviewResult.Action.PASS, review(context,
                "请申请完成第一步。", "你已拒绝这次完成申请，第一步仍为进行中，未修改进度。",
                AgentTaskStepStatus.IN_PROGRESS).getAction());
    }

    // 继续讲解的邀请不是数据库进度变更，不能要求删除正常教学问题或补调工具。
    @Test
    void allowsTeachingAndInvitationWithoutChangingProgress() {
        assertEquals(AnswerReviewResult.Action.PASS, review(new AgentRunContext(),
                "只解释 Collection 与 Map 的区别，先不要更改步骤状态。",
                "Collection 存放元素，Map 存放键值映射。这是内容讲解，未修改步骤状态。你想继续看 Map 的例子，还是先问一个问题？",
                AgentTaskStepStatus.PENDING).getAction());
    }

    // 用生产客户端和审查服务发请求；只记录判定类型，配置中的密钥不进入日志。
    private AnswerReviewResult review(AgentRunContext context, String userMessage, String draft, AgentTaskStepStatus status) {
        AliyunLlmProperties properties = new AliyunLlmProperties();
        properties.setBaseUrl(env("ALIYUN_LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));
        properties.setModel(env("ALIYUN_LLM_MODEL", "qwen-plus"));
        properties.setApiKey(env("ALIYUN_LLM_API_KEY", System.getenv("ALIYUN_EMBEDDING_API_KEY")));
        assertNotNull(properties.getApiKey(), "真实测试需要配置模型密钥");
        var client = spy(new AliyunLlmClient(properties, new ToolRegistry(List.of())));
        AtomicReference<String> rawOutput = new AtomicReference<>();
        // 不替换真实响应；只有合成测试失败时记录原文，排查模型是否输出了不完整 JSON。
        doAnswer(invocation -> {
            Object response = invocation.callRealMethod();
            if (response instanceof com.yjjoker.learningagent.harness.llm.model.TextLlmResponse text) {
                rawOutput.set(text.content());
            }
            return response;
        }).when(client).generateWithoutTools(anyList());
        var service = new LlmAnswerReviewService(client, new LlmRetryExecutor());
        SessionGoalStepProgress step = new SessionGoalStepProgress();
        step.setStepRef("goal-1-v2-step-1");
        step.setPosition(1);
        step.setDescription("认识集合整体结构");
        step.setCompletionCriteria("用户能解释 Collection 与 Map 的区别");
        step.setStatus(status);
        if (status == AgentTaskStepStatus.COMPLETED) step.setResultSummary("上一轮用户确认通过，未独立验证掌握。");
        SessionGoalProgress progress = new SessionGoalProgress();
        progress.setGoal("学习 Java 集合");
        progress.setPlanVersion(2);
        progress.setSteps(List.of(step));
        AnswerReviewResult result = service.review(context, new AnswerReviewRequest(
                userMessage, draft, List.of(LlmMessage.user(userMessage)), progress));
        // 这里的理由只涉及本文件的合成样本，便于真实验收定位分类错误；生产日志不记录它。
        log.info("真实 Aliyun 审查验收，runId={}，action={}，databaseStatus={}，syntheticReason={}",
                context.getRunId(), result.getAction(), status, result.getReason());
        if (result.getAction() == AnswerReviewResult.Action.UNAVAILABLE) {
            log.warn("合成审查格式诊断，runId={}，syntheticOutput={}", context.getRunId(), rawOutput.get());
        }
        return result;
    }

    // 与应用使用相同环境变量，未设置的非敏感选项使用生产默认值。
    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
