package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 前置 Hook 分别表达允许、拒绝、需要审批；需要审批不代表权限校验已经通过。
// 预期内的校验失败不再依赖抛异常，Harness 可以据此选择重试或直接结束。
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ToolCallHookResult {

    // 允许、需要审批、拒绝三者互斥；false 不再直接等同于拒绝。
    private final boolean allowed;
    private final boolean approvalRequired;
    private final String approvalReason;

    // 拒绝信息与工具失败共用同一个错误模型。
    @Getter(AccessLevel.NONE)
    private final HarnessError error;

    // 本 Hook 不阻止调用，仍需通过其他前置检查。
    public static ToolCallHookResult allow() {
        return new ToolCallHookResult(true, false, null, null);
    }

    // 只提出审批要求，Harness 仍会继续执行后面的权限检查。
    public static ToolCallHookResult requireApproval(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("审批必须说明原因");
        }
        return new ToolCallHookResult(false, true, reason, null);
    }

    // 参数错误可交回模型修正；权限错误可以直接结束任务。
    public static ToolCallHookResult reject(String errorCode, String message, boolean retryable) {
        return reject(HarnessError.of(
                errorCode,
                message,
                retryable,
                HarnessErrorSource.HOOK
        ));
    }

    // 保留统一错误对象，审批要求不能覆盖真正的拒绝。
    public static ToolCallHookResult reject(HarnessError error) {
        if (error == null) {
            throw new IllegalArgumentException("Hook 拒绝结果必须包含 HarnessError");
        }
        return new ToolCallHookResult(false, false, null, error);
    }

    // 保留旧的调用和序列化字段，调用方暂时不需要感知内部字段迁移。
    public String getErrorCode() {
        return error == null ? null : error.getErrorCode();
    }

    // 返回拒绝说明；审批原因单独保存在 approvalReason 中。
    public String getMessage() {
        return error == null ? null : error.getMessage();
    }

    // 只判断失败能否修正，等待审批不属于重试错误。
    public boolean isRetryable() {
        return error != null && error.isRetryable();
    }

    // 后端沿用统一错误对象，避免重新拼装时丢失错误来源。
    public HarnessError error() {
        return error;
    }
}
