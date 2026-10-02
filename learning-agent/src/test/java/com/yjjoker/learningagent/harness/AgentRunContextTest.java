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
}
