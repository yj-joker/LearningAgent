package com.yjjoker.learningagent.harness.error;

import lombok.Getter;

import java.util.Arrays;

// 统一维护 Harness 关注的稳定错误编码，模型和程序不需要解析自然语言。
@Getter
public enum HarnessErrorCode {
    INVALID_ARGUMENT("INVALID_ARGUMENT"),
    INVALID_TOOL_ARGUMENTS("INVALID_TOOL_ARGUMENTS"),
    TOOL_ARGUMENTS_TOO_LARGE("TOOL_ARGUMENTS_TOO_LARGE"),
    TOOL_NOT_FOUND("TOOL_NOT_FOUND"),
    TOOL_EXECUTION_FAILED("TOOL_EXECUTION_FAILED"),
    ORIGINAL_TOOL_RESULT_NOT_FOUND("ORIGINAL_TOOL_RESULT_NOT_FOUND"),
    INVALID_RECOVERY_REFERENCE("INVALID_RECOVERY_REFERENCE"),
    RECOVERY_CALL_LIMIT_EXCEEDED("RECOVERY_CALL_LIMIT_EXCEEDED"),
    RECOVERY_CHARACTER_LIMIT_EXCEEDED("RECOVERY_CHARACTER_LIMIT_EXCEEDED"),
    HOOK_REJECTED("HOOK_REJECTED"),
    LLM_REQUEST_FAILED("LLM_REQUEST_FAILED"),
    LLM_TIMEOUT("LLM_TIMEOUT"),
    LLM_RATE_LIMITED("LLM_RATE_LIMITED"),
    LLM_SERVER_ERROR("LLM_SERVER_ERROR"),
    LLM_AUTHENTICATION_FAILED("LLM_AUTHENTICATION_FAILED"),
    LLM_INVALID_REQUEST("LLM_INVALID_REQUEST"),
    LLM_INVALID_RESPONSE("LLM_INVALID_RESPONSE"),
    CONTEXT_WINDOW_EXCEEDED("CONTEXT_WINDOW_EXCEEDED"),
    HARNESS_LIMIT_EXCEEDED("HARNESS_LIMIT_EXCEEDED"),
    INTERNAL_ERROR("INTERNAL_ERROR");

    private final String code;

    HarnessErrorCode(String code) {
        this.code = code;
    }

    // 兼容当前已经存在的字符串错误码；未知编码暂时原样保留在 HarnessError 中。
    public static HarnessErrorCode fromCode(String code) {
        return Arrays.stream(values())
                .filter(errorCode -> errorCode.code.equals(code))
                .findFirst()
                .orElse(INTERNAL_ERROR);
    }
}
