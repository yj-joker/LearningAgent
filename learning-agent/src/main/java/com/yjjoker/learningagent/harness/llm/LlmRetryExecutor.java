package com.yjjoker.learningagent.harness.llm;

import com.yjjoker.learningagent.config.HarnessLlmRetryProperties;
import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

// 统一执行 LLM 请求重试，主 Agent Loop 和摘要请求共用这套规则。
@Component
@Slf4j
public class LlmRetryExecutor {

    // 重试执行器只读取配置，不负责判断具体 HTTP 状态码。
    private final HarnessLlmRetryProperties properties;

    // 生产环境通过 Spring 注入配置，保证环境变量能生效。
    public LlmRetryExecutor(HarnessLlmRetryProperties properties) {
        this.properties = properties;
    }

    // 测试和兼容旧构造方法使用默认配置；生产环境由 Spring 注入配置对象。
    public LlmRetryExecutor() {
        this(new HarnessLlmRetryProperties());
    }

    public LlmResponse generate(LlmClient client, List<LlmMessage> messages) {
        // Agent Loop 的每一轮模型请求都经过统一重试入口。
        return execute("agent", () -> client.generate(messages));
    }

    public LlmResponse generateWithoutTools(LlmClient client, List<LlmMessage> messages) {
        // 摘要请求也需要重试，但仍保持“不提供业务工具”的调用方式。
        return execute("summary", () -> client.generateWithoutTools(messages));
    }

    private LlmResponse execute(String requestType, Supplier<LlmResponse> request) {
        // 防御非法配置，确保至少尝试一次且等待时间不会超过上限。
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        long maxBackoffMillis = Math.max(0, properties.getMaxBackoffMillis());
        long backoffMillis = Math.clamp(properties.getInitialBackoffMillis(), 0,
                maxBackoffMillis
        );

        // 每次重试使用同一份消息快照，避免请求过程中修改上下文造成前后不一致。
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                LlmResponse response = request.get();
                if (attempt > 1) {
                    log.info("LLM 请求重试成功，requestType={}，attempt={}，maxAttempts={}",
                            requestType, attempt, maxAttempts);
                }
                return response;
            } catch (HarnessException exception) {
                // 只有统一错误模型明确标记 retryable=true，才允许再次请求。
                if (!exception.getError().isRetryable() || attempt == maxAttempts) {
                    log.warn("LLM 请求结束，requestType={}，attempt={}，maxAttempts={}，errorCode={}，retryable={}",
                            requestType, attempt, maxAttempts,
                            exception.getErrorCode(), exception.getError().isRetryable());
                    throw exception;
                }

                // 当前尝试仍有预算时，先记录原因，再等待后进入下一次请求。
                log.warn("LLM 请求失败，将进行重试，requestType={}，attempt={}，maxAttempts={}，errorCode={}，backoffMillis={}",
                        requestType, attempt, maxAttempts,
                        exception.getErrorCode(), backoffMillis);
                sleepBeforeRetry(backoffMillis);
                backoffMillis = nextBackoff(backoffMillis, maxBackoffMillis);
            }
        }

        // 循环必然在成功或抛出异常时结束，这里只是防止未来修改循环后静默返回 null。
        throw new IllegalStateException("LLM 重试流程异常结束");
    }

    private long nextBackoff(long current, long maximum) {
        // 达到上限后保持不变；0 表示测试或配置要求不等待。
        if (maximum == 0) {
            return 0;
        }
        if (current == 0) {
            return Math.min(1, maximum);
        }
        return Math.min(maximum, current * 2);
    }

    private void sleepBeforeRetry(long backoffMillis) {
        // 无等待配置时直接重试，避免无意义地调用 Thread.sleep。
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException exception) {
            // 保留中断标记，让上层线程池知道当前任务被要求停止。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 重试等待被中断", exception);
        }
    }
}
