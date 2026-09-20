package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Spring 会把这个实现类注册为 Bean，再放入 Harness 构造方法接收的 Hook 列表中。
// 这个 Hook 只负责记录运行轨迹，不修改参数，也不决定工具能否执行。
@Slf4j
@Component
public class LoggingAgentHook implements AgentHook {

    @Override
    public ToolCallHookResult beforeToolExecution(AgentRunContext context, ToolCall toolCall) {
        // 不记录 arguments，避免用户输入、查询条件等敏感参数直接进入日志。
        log.info("Agent 准备执行工具，runId={}, toolName={}, toolCallId={}",
                context.getRunId(), toolCall.name(), toolCall.id());
        return ToolCallHookResult.allow();
    }

    @Override
    public void afterToolExecution(AgentRunContext context,
                                   ToolCall toolCall,
                                   ToolExecutionResult result) {
        // 只记录执行状态和错误类型，不记录 content，避免真实学习数据进入普通运行日志。
        log.info("Agent 工具执行完成，runId={}, toolName={}, toolCallId={}, successful={}, errorCode={}",
                context.getRunId(),
                toolCall.name(),
                toolCall.id(),
                result.isSuccess(),
                result.getErrorCode());
    }

    @Override
    public void afterRun(AgentRunContext context) {
        log.info("Agent 任务结束，runId={}, successful={}, executedTools={}, durationMs={}, failureType={}",
                context.getRunId(),
                context.isSuccessful(),
                context.getExecutedToolNames(),
                context.getElapsedMilliseconds(),
                context.getFailureType());
    }
}
