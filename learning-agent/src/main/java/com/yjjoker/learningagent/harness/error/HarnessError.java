package com.yjjoker.learningagent.harness.error;

import lombok.Getter;

// HarnessError 是跨工具、Hook、LLM 和上下文层传递的统一错误描述。
@Getter
public class HarnessError {

    // 仍保留字符串，保证已有自定义工具的旧错误码不会被强制改名。
    private final String errorCode;
    private final String message;
    private final boolean retryable;
    private final HarnessErrorSource source;

    private HarnessError(String errorCode,
                         String message,
                         boolean retryable,
                         HarnessErrorSource source) {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("Harness 错误码不能为空");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Harness 错误消息不能为空");
        }
        if (source == null) {
            throw new IllegalArgumentException("Harness 错误来源不能为空");
        }
        this.errorCode = errorCode;
        this.message = message;
        this.retryable = retryable;
        this.source = source;
    }

    // 标准错误使用枚举创建，避免在新代码中到处散落字符串常量。
    public static HarnessError of(HarnessErrorCode errorCode,
                                  String message,
                                  boolean retryable,
                                  HarnessErrorSource source) {
        if (errorCode == null) {
            throw new IllegalArgumentException("Harness 错误编码不能为空");
        }
        return new HarnessError(errorCode.getCode(), message, retryable, source);
    }

    // 保留字符串入口，兼容已有工具和恢复流程的历史错误码。
    public static HarnessError of(String errorCode,
                                  String message,
                                  boolean retryable,
                                  HarnessErrorSource source) {
        return new HarnessError(errorCode, message, retryable, source);
    }
}
