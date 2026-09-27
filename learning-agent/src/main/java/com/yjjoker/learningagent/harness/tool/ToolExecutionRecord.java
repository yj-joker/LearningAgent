package com.yjjoker.learningagent.harness.tool;

import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 一次工具请求的不可变记录；原始参数和结果只留在本次请求里，不直接写入日志或提取提示词。
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ToolExecutionRecord {
    // 顺序号区分同一个工具的多次调用，不用工具名称当唯一标识。
    private final int sequence;
    private final String toolCallId;
    private final String toolName;
    private final String arguments;
    // 由 Java 工具声明，模型不能把查询伪装成写入，也不能把写入伪装成查询。
    private final boolean memoryWriteTool;
    private final Status status;
    // 成功或业务失败保存原结果；系统异常没有可确认的业务结果。
    private final ToolExecutionResult result;
    private final String failureType;

    // 区分尚未执行、正在执行、成功、业务失败、被拒绝和异常，不能把“调用过”当成“成功”。
    public enum Status { REQUESTED, STARTED, SUCCEEDED, FAILED, REJECTED, ERROR }

    // Harness 收到请求时先登记，之后才能准确记录拦截或异常。
    public static ToolExecutionRecord requested(int sequence, ToolCall call) {
        return new ToolExecutionRecord(sequence, call.id(), call.name(), call.arguments(),
                false, Status.REQUESTED, null, null);
    }

    // 找到实际 Java 工具后补充分类，保留原始请求和当前状态。
    public ToolExecutionRecord withMemoryWriteTool(boolean value) {
        return new ToolExecutionRecord(sequence, toolCallId, toolName, arguments, value, status, result, failureType);
    }

    // 返回新记录而不修改旧对象，让其他组件已经拿到的快照保持不变。
    public ToolExecutionRecord withOutcome(Status nextStatus, ToolExecutionResult outcome, String errorType) {
        return new ToolExecutionRecord(sequence, toolCallId, toolName, arguments, memoryWriteTool, nextStatus, outcome, errorType);
    }

    // 只有终态才能交给提取模型；REQUESTED 和 STARTED 表示轨迹还不完整。
    public boolean isFinished() {
        return status != Status.REQUESTED && status != Status.STARTED;
    }
}
