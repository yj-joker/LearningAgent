package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 收集正常返回的工具结果；Hook 不保存共享列表，所有记录都属于传入的本轮 context。
@Component
@Slf4j
public class ToolExecutionRecordingHook implements AgentHook {
    // 成功和返回业务错误都会进入这里；拦截、系统异常由 Harness 在对应分支补记。
    @Override
    public void afterToolExecution(AgentRunContext context, ToolCall toolCall, ToolExecutionResult result) {
        context.completeToolExecution(toolCall, result);
        // 只记录状态和长度，不把工具参数、正文或错误消息打印到普通日志。
        log.info("工具执行轨迹已记录，runId={}，toolName={}，successful={}，resultCharacters={}",
                context.getRunId(), toolCall.name(), result.isSuccess(),
                result.getContent() == null ? 0 : result.getContent().length());
    }
}
