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
    // 只保存后端读到的审批决定；null 表示未记录，不能猜测成已批准或无需审批。
    private final String approvalDecision;
    private final Status status;
    // 成功或业务失败保存原结果；系统异常没有可确认的业务结果。
    private final ToolExecutionResult result;
    private final String failureType;

    // VALIDATION_FAILED 表示工具未执行且输入预检没通过，不等于用户拒绝。
    public enum Status { REQUESTED, STARTED, SUCCEEDED, FAILED, VALIDATION_FAILED, REJECTED, ERROR }

    // 审查模型和最终 Hook 共用这一判断，避免两处对同一错误给出相反的执行许可。
    public boolean blocksContinuation() {
        return switch (status) {
            // 保留真正拒绝和未知结果，不能用后一次同名工具成功把它们覆盖。
            case REQUESTED, STARTED, REJECTED, ERROR -> true;
            case VALIDATION_FAILED, FAILED -> result == null || result.isSuccess() || !result.isRetryable();
            case SUCCEEDED -> result == null || !result.isSuccess();
        };
    }

    // Harness 收到请求时先登记，之后才能准确记录拦截或异常。
    public static ToolExecutionRecord requested(int sequence, ToolCall call) {
        return new ToolExecutionRecord(sequence, call.id(), call.name(), call.arguments(),
                false, null, Status.REQUESTED, null, null);
    }

    // 找到实际 Java 工具后补充分类，保留原始请求和当前状态。
    public ToolExecutionRecord withMemoryWriteTool(boolean value) {
        return new ToolExecutionRecord(sequence, toolCallId, toolName, arguments, value, approvalDecision, status, result, failureType);
    }

    // 审批和执行分别记录：批准后仍可能校验失败，批准本身不等于操作成功。
    public ToolExecutionRecord withApprovalDecision(String decision) {
        if (decision != null && !"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new IllegalArgumentException("工具审批决定必须是批准或拒绝");
        }
        return new ToolExecutionRecord(sequence, toolCallId, toolName, arguments, memoryWriteTool,
                decision, status, result, failureType);
    }

    // 返回新记录而不修改旧对象，让其他组件已经拿到的快照保持不变。
    public ToolExecutionRecord withOutcome(Status nextStatus, ToolExecutionResult outcome, String errorType) {
        return new ToolExecutionRecord(sequence, toolCallId, toolName, arguments, memoryWriteTool,
                approvalDecision, nextStatus, outcome, errorType);
    }

    // 待审批调用保存在检查点中；运行轨迹只判断这次实际尝试是否已经结束。
    public boolean isFinished() {
        return status != Status.REQUESTED && status != Status.STARTED;
    }
}
