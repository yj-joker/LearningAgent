package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

// beforeToolExecution Hook 使用这个对象明确表示“允许”或“拒绝”。
// 预期内的校验失败不再依赖抛异常，Harness 可以据此选择重试或直接结束。
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ToolCallHookResult {

    // true 表示允许，false 表示拒绝
    private final boolean allowed;

    // 拒绝信息与工具失败共用同一个错误模型。
    @Getter(AccessLevel.NONE)
    private final HarnessError error;

    public static ToolCallHookResult allow() {
        return new ToolCallHookResult(true, null);
    }

    public static ToolCallHookResult reject(String errorCode, String message, boolean retryable) {
        return reject(HarnessError.of(
                errorCode,
                message,
                retryable,
                HarnessErrorSource.HOOK
        ));
    }

    public static ToolCallHookResult reject(HarnessError error) {
        if (error == null) {
            throw new IllegalArgumentException("Hook 拒绝结果必须包含 HarnessError");
        }
        return new ToolCallHookResult(false, error);
    }

    // 保留旧的调用和序列化字段，调用方暂时不需要感知内部字段迁移。
    public String getErrorCode() {
        return error == null ? null : error.getErrorCode();
    }

    public String getMessage() {
        return error == null ? null : error.getMessage();
    }

    public boolean isRetryable() {
        return error != null && error.isRetryable();
    }

    public HarnessError error() {
        return error;
    }
}
