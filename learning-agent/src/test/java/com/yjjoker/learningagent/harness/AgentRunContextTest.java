package com.yjjoker.learningagent.harness;

import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.tool.ToolExecutionRecord;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// 使用真实工具轨迹验证“调用过”和“成功执行”必须分开判断。
class AgentRunContextTest {

    // 工具请求和拒绝都不能被进度守卫误判成成功。
    @Test
    void rejectedToolIsNotSuccessful() {
        AgentRunContext context = new AgentRunContext();
        ToolCall call = new ToolCall("progress-1", "update_task_progress", "{}");
        context.requestToolExecution(call);
        context.rejectToolExecution(call, ToolExecutionResult.failure("REJECTED", "需要审批", true));

        assertFalse(context.hasSuccessfulTool("update_task_progress"));
    }

    // 只有成功结果才允许模型说明结构化进度已经更新。
    @Test
    void successfulToolIsRecognized() {
        AgentRunContext context = new AgentRunContext();
        ToolCall call = new ToolCall("progress-1", "update_task_progress", "{}");
        context.requestToolExecution(call);
        context.startToolExecution(call);
        context.completeToolExecution(call, ToolExecutionResult.success("已更新"));

        assertTrue(context.hasSuccessfulTool("update_task_progress"));
        assertEquals(ToolExecutionRecord.Status.SUCCEEDED,
                context.getToolExecutions().getFirst().getStatus());
    }

    // 参数不合法但尚未执行时，允许修正输入，不算权限或用户拒绝。
    @Test
    void retryableValidationDoesNotBlockContinuation() {
        AgentRunContext context = new AgentRunContext();
        ToolCall call = new ToolCall("bad-input", "update_task_progress", "{}");
        context.requestToolExecution(call);
        context.failToolValidation(call, ToolExecutionResult.failure("INVALID_INPUT", "请修正参数", true));

        assertTrue(context.hasCompleteToolHistory());
        assertFalse(context.hasRejectedTool(call.name()));
        assertFalse(context.hasSuccessfulTool(call.name()));
        assertFalse(context.hasBlockingToolOutcome());
        assertTrue(context.getExecutedToolNames().isEmpty());
    }

    // 工具已经开始执行就可能产生副作用，不能再伪装成“尚未执行的参数错误”。
    @Test
    void startedCallCannotBeReclassifiedAsValidationFailure() {
        AgentRunContext context = new AgentRunContext();
        ToolCall call = new ToolCall("started", "write", "{}");
        context.requestToolExecution(call);
        context.startToolExecution(call);
        assertThrows(IllegalStateException.class, () -> context.failToolValidation(call,
                ToolExecutionResult.failure("INVALID_INPUT", "请修正参数", true)));
        assertTrue(context.hasBlockingToolOutcome());
    }

    // 同名工具可能操作不同目标；后一次成功不能抹掉前一次真正的拒绝。
    @Test
    void laterSuccessDoesNotEraseRejectionEvenIfMarkedRetryable() {
        AgentRunContext context = new AgentRunContext();
        ToolCall denied = new ToolCall("denied", "write", "{\"target\":1}");
        context.requestToolExecution(denied);
        context.rejectToolExecution(denied, ToolExecutionResult.failure("DENIED", "无权限", true));
        ToolCall allowed = new ToolCall("allowed", "write", "{\"target\":2}");
        context.requestToolExecution(allowed);
        context.startToolExecution(allowed);
        context.completeToolExecution(allowed, ToolExecutionResult.success("已写入"));

        assertEquals(2, context.getToolExecutions().size());
        assertTrue(context.hasSuccessfulTool("write"));
        assertTrue(context.hasRejectedTool("write"));
        assertTrue(context.hasBlockingToolOutcome());
    }

    // 无法修正的输入和执行结果不确定，都不能自动补调用。
    @Test
    void nonRetryableValidationAndUnknownOutcomeStayBlocked() {
        AgentRunContext invalid = new AgentRunContext();
        ToolCall call = new ToolCall("large-input", "write", "{}");
        invalid.requestToolExecution(call);
        invalid.failToolValidation(call, ToolExecutionResult.failure("TOO_LARGE", "参数超长", false));
        assertTrue(invalid.hasBlockingToolOutcome());

        AgentRunContext unknown = new AgentRunContext();
        unknown.requestToolExecution(call);
        unknown.startToolExecution(call);
        unknown.markFailed(new IllegalStateException("结果未知"));
        assertTrue(unknown.hasBlockingToolOutcome());
    }

    // 已批准不保证执行成功；恢复后参数或权限变化，仍然保留真正失败。
    @Test
    void approvalIsNotExecutionAndSurvivesFailedCheck() {
        AgentRunContext context = new AgentRunContext();
        ToolCall call = new ToolCall("approved", "write", "{}");
        context.requestToolExecution(call);
        context.recordToolApproval(call, "APPROVED");
        assertFalse(context.hasSuccessfulTool("write"));
        context.rejectToolExecution(call, ToolExecutionResult.failure("NO_PERMISSION", "权限已变化", false));
        assertEquals("APPROVED", context.getToolExecutions().getFirst().getApprovalDecision());
        assertTrue(context.hasBlockingToolOutcome());
        assertFalse(context.hasSuccessfulTool("write"));
    }
}
