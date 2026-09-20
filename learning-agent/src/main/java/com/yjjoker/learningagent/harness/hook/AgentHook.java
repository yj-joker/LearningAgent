package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;

// AgentHook 表示 Agent Loop 中预留的扩展点。
// 当前阶段只观察流程；后续可以增加权限检查、参数校验等实现，而不必把逻辑都塞进 Harness。
public interface AgentHook {

    // Harness 找到工具、但尚未执行 execute() 时调用。
    // 现在的日志 Hook 只记录工具名称；将来的安全 Hook 可以在这里拒绝不允许的调用。
    default ToolCallHookResult beforeToolExecution(AgentRunContext context, ToolCall toolCall) {
        return ToolCallHookResult.allow();
    }

    // 工具的 execute() 正常返回后、结果发送给 LLM 之前调用。
    // result 能告诉 Hook 工具是成功还是返回了可处理的业务错误，但 Hook 当前不能修改这个结果。
    default void afterToolExecution(AgentRunContext context,
                                    ToolCall toolCall,
                                    ToolExecutionResult result) {
    }

    // 无论任务成功还是异常结束，Harness 都会调用一次该方法。
    // 通过 context 可以查看本次任务真正执行过哪些工具以及是否成功。
    default void afterRun(AgentRunContext context) {
    }
}
