package com.yjjoker.learningagent.harness.llm;

import com.yjjoker.learningagent.config.HarnessLlmRetryProperties;
import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorCode;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LlmRetryExecutorTest {

    @Test
    void shouldRetryTransientLlmFailureAndReturnLaterSuccess() {
        // 第一次模拟临时失败，第二次返回文本，验证可重试错误会再次调用客户端。
        HarnessLlmRetryProperties properties = properties(3);
        LlmRetryExecutor executor = new LlmRetryExecutor(properties);
        LlmClient client = mock(LlmClient.class);
        LlmResponse expected = new TextLlmResponse("success");

        when(client.generate(any())).thenThrow(llmFailure(true))
                .thenReturn(expected);

        LlmResponse actual = executor.generate(client, List.of(LlmMessage.user("hello")));

        assertEquals(expected, actual);
        verify(client, times(2)).generate(any());
    }

    @Test
    void shouldNotRetryNonRetryableLlmFailure() {
        // 鉴权或参数类错误不可通过重复请求修复，因此只能调用一次。
        HarnessLlmRetryProperties properties = properties(3);
        LlmRetryExecutor executor = new LlmRetryExecutor(properties);
        LlmClient client = mock(LlmClient.class);
        when(client.generate(any())).thenThrow(llmFailure(false));

        assertThrows(HarnessException.class,
                () -> executor.generate(client, List.of(LlmMessage.user("hello"))));

        verify(client, times(1)).generate(any());
    }

    @Test
    void shouldStopAfterMaximumAttempts() {
        // 每次都返回可重试错误，验证达到上限后不会无限循环。
        HarnessLlmRetryProperties properties = properties(2);
        LlmRetryExecutor executor = new LlmRetryExecutor(properties);
        LlmClient client = mock(LlmClient.class);
        when(client.generate(any())).thenThrow(llmFailure(true));

        assertThrows(HarnessException.class,
                () -> executor.generate(client, List.of(LlmMessage.user("hello"))));

        verify(client, times(2)).generate(any());
    }

    private HarnessLlmRetryProperties properties(int maxAttempts) {
        // 测试只关心分支和次数，把等待时间设为 0 以保持测试快速稳定。
        HarnessLlmRetryProperties properties = new HarnessLlmRetryProperties();
        properties.setMaxAttempts(maxAttempts);
        // 测试不等待真实退避时间，只验证重试次数和分支判断。
        properties.setInitialBackoffMillis(0);
        properties.setMaxBackoffMillis(0);
        return properties;
    }

    private HarnessException llmFailure(boolean retryable) {
        // 使用统一错误对象模拟 LlmClient 已完成的错误分类。
        return new HarnessException(HarnessError.of(
                HarnessErrorCode.LLM_REQUEST_FAILED,
                "test llm failure",
                retryable,
                HarnessErrorSource.LLM
        ));
    }
}
