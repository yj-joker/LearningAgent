package com.yjjoker.learningagent.harness.review;

import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.*;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalProgress;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalStepProgress;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 检查真实审查协议和输入投影；模型使用替身，不把这些测试冒充 Aliyun 实测。
class LlmAnswerReviewServiceTest {
    private final LlmClient client = mock(LlmClient.class);
    private final LlmAnswerReviewService service = new LlmAnswerReviewService(client, new LlmRetryExecutor());

    // 四种合法意见均可解析，且审查始终不提供业务工具。
    @ParameterizedTest
    @ValueSource(strings = {"PASS", "REWRITE", "CONTINUE", "CLARIFY"})
    void parsesOnlySupportedActions(String action) {
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(
                "{\"action\":\"" + action + "\",\"reason\":\"核对结果\",\"instruction\":\"按事实处理\"}"));
        assertEquals(AnswerReviewResult.Action.valueOf(action), service.review(new AgentRunContext(), request("回答")).getAction());
        verify(client, never()).generate(any());
    }

    // 格式失败、重复字段和未知动作都保守结束，不再套一个无限 JSON 修复循环。
    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "[]", "{}",
            "{\"action\":\"PASS\",\"action\":\"CONTINUE\",\"reason\":\"\",\"instruction\":\"继续\"}",
            "{\"action\":\"PASS\",\"reason\":\"\",\"instruction\":\"\"} {}",
            "{\"action\":\"UNAVAILABLE\",\"reason\":\"\",\"instruction\":\"\"}",
            "{\"action\":\"CONTINUE\",\"reason\":\"\",\"instruction\":\"\"}",
            "{\"action\":\"PASS\",\"reason\":false,\"instruction\":\"\"}"})
    void invalidOutputIsNotAllowed(String output) {
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(output));
        assertEquals(AnswerReviewResult.Action.UNAVAILABLE, service.review(new AgentRunContext(), request("回答")).getAction());
        verify(client).generateWithoutTools(any());
    }

    // 工具结果带状态与截断标记；历史摘要、系统消息和完整大正文不能混进审查证据。
    @Test
    void sendsBoundedEvidenceAndAuthoritativeProgress() {
        AgentRunContext context = new AgentRunContext();
        ToolCall call = new ToolCall("id", "search", "x".repeat(1400));
        context.requestToolExecution(call);
        context.completeToolExecution(call, ToolExecutionResult.success("y".repeat(3000)));
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(
                "{\"action\":\"PASS\",\"reason\":\"一致\",\"instruction\":\"\"}"));
        var request = new AnswerReviewRequest("继续讲解", "回答", List.of(
                LlmMessage.system("不能外发的系统块"), LlmMessage.summary("摘要不是用户原话"),
                LlmMessage.user("上次问题"), LlmMessage.assistant("上次回答")), request("回答").getProgress());
        service.review(context, request);
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(client).generateWithoutTools(sent.capture());
        var input = new JsonMapper().readTree(sent.getValue().getLast().getContent());
        assertEquals(2, input.path("recentDialogue").size());
        assertEquals("COMPLETED", input.path("databaseProgress").path("steps").get(0).path("status").asString());
        var record = input.path("toolExecutions").get(0);
        assertEquals("SUCCEEDED", record.path("status").asString());
        assertTrue(record.path("argumentsTruncated").asBoolean());
        assertTrue(record.path("resultTruncated").asBoolean());
        assertEquals(1500, record.path("result").asString().length());
    }

    // 已有完成状态会发送给审查；零调用不是“过去也没有执行”的证据。
    @Test
    void keepsPastCompletionWhenNoToolRanThisTurn() {
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(
                "{\"action\":\"PASS\",\"reason\":\"数据库已完成\",\"instruction\":\"\"}"));
        assertEquals(AnswerReviewResult.Action.PASS,
                service.review(new AgentRunContext(), request("第一步已完成")).getAction());
    }

    // 输入超过预算或轨迹不完整时不发送模型，更不能静默截掉关键证据后放行。
    @Test
    void refusesOversizedOrIncompleteEvidence() {
        assertEquals(AnswerReviewResult.Action.UNAVAILABLE,
                service.review(new AgentRunContext(), request("大".repeat(50000))).getAction());
        AgentRunContext incomplete = new AgentRunContext();
        incomplete.markToolHistoryIncomplete();
        assertEquals(AnswerReviewResult.Action.UNAVAILABLE, service.review(incomplete, request("回答")).getAction());
        verifyNoInteractions(client);
    }

    // 网络调用最终失败或返回工具请求时，不会把未审查回答当作通过。
    @Test
    void handlesModelFailureAndUnexpectedTools() {
        when(client.generateWithoutTools(any())).thenThrow(new IllegalStateException("模拟网络失败"))
                .thenReturn(new ToolCallLlmResponse(List.of(new ToolCall("bad", "write", "{}"))));
        assertEquals(AnswerReviewResult.Action.UNAVAILABLE, service.review(new AgentRunContext(), request("回答")).getAction());
        assertEquals(AnswerReviewResult.Action.UNAVAILABLE, service.review(new AgentRunContext(), request("回答")).getAction());
    }

    // 构造一份后端进度，不从自然语言“完成”二字推断状态。
    private AnswerReviewRequest request(String answer) {
        SessionGoalStepProgress step = new SessionGoalStepProgress();
        step.setStatus(AgentTaskStepStatus.COMPLETED);
        step.setCompletionCriteria("用户能解释两大体系");
        SessionGoalProgress progress = new SessionGoalProgress();
        progress.setSteps(List.of(step));
        return new AnswerReviewRequest("讲解集合", answer, List.of(LlmMessage.user("讲解集合")), progress);
    }
}
