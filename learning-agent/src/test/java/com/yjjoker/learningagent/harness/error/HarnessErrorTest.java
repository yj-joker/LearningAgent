package com.yjjoker.learningagent.harness.error;

import com.yjjoker.learningagent.harness.hook.ToolCallHookResult;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessErrorTest {

    @Test
    void shouldKeepOneErrorShapeAcrossToolAndHookResults() {
        HarnessError error = HarnessError.of(
                HarnessErrorCode.INVALID_ARGUMENT,
                "参数不正确",
                true,
                HarnessErrorSource.TOOL
        );

        ToolExecutionResult toolResult = ToolExecutionResult.failure(error);
        ToolCallHookResult hookResult = ToolCallHookResult.reject(error);

        assertFalse(toolResult.isSuccess());
        assertEquals("INVALID_ARGUMENT", toolResult.getErrorCode());
        assertEquals("参数不正确", toolResult.getMessage());
        assertTrue(toolResult.isRetryable());
        assertSame(error, toolResult.error());

        assertFalse(hookResult.isAllowed());
        assertEquals("INVALID_ARGUMENT", hookResult.getErrorCode());
        assertEquals("参数不正确", hookResult.getMessage());
        assertTrue(hookResult.isRetryable());
        assertSame(error, hookResult.error());
    }

    @Test
    void shouldPreserveLegacyStringErrorCode() {
        ToolExecutionResult result = ToolExecutionResult.failure(
                "CUSTOM_TOOL_ERROR",
                "自定义工具失败",
                false
        );

        assertEquals("CUSTOM_TOOL_ERROR", result.getErrorCode());
        assertEquals(HarnessErrorSource.TOOL, result.error().getSource());
        assertFalse(result.isRetryable());
    }

    @Test
    void shouldWrapSystemFailureWithStructuredError() {
        HarnessError error = HarnessError.of(
                HarnessErrorCode.LLM_REQUEST_FAILED,
                "模型请求失败",
                true,
                HarnessErrorSource.LLM
        );
        HarnessException exception = new HarnessException(error);

        assertEquals("LLM_REQUEST_FAILED", exception.getErrorCode());
        assertEquals("模型请求失败", exception.getMessage());
        assertSame(error, exception.getError());
    }
}
