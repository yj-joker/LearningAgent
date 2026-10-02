package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.harness.review.AnswerReviewRequest;

// AgentHook 表示 Agent Loop 中预留的扩展点。
// Hook 提出校验、拒绝或审批要求；是否执行、暂停和恢复由 Harness 决定。
public interface AgentHook {

    // Harness 找到工具、但尚未执行 execute() 时调用。
    // 整批预检和审批后复查都可能调用；这里不能执行写入等业务副作用。
    default ToolCallHookResult beforeToolExecution(AgentRunContext context, ToolCall toolCall) {
        return ToolCallHookResult.allow();
    }

    // 工具的 execute() 正常返回后、结果发送给 LLM 之前调用。
    // result 能告诉 Hook 工具是成功还是返回了可处理的业务错误，但 Hook 当前不能修改这个结果。
    default void afterToolExecution(AgentRunContext context,
                                    ToolCall toolCall,
                                    ToolExecutionResult result) {
    }

    // 模型已经返回普通文本、但 Harness 还没有保存最终回答时调用。
    // Hook 可以要求重新请求模型，或返回后端保护性回答，但不直接写数据库。
    default FinalAnswerHookResult beforeFinalAnswer(AgentRunContext context, AnswerReviewRequest request) {
        return FinalAnswerHookResult.allow();
    }

    // 无论任务成功还是异常结束，Harness 都会调用一次该方法。
    // 通过 context 可以查看本次任务真正执行过哪些工具以及是否成功。
    default void afterRun(AgentRunContext context) {
    }
}
