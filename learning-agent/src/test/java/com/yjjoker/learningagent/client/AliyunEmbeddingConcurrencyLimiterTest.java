package com.yjjoker.learningagent.client;

import com.yjjoker.learningagent.config.AliyunEmbeddingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("阿里云 Embedding 并发限制器测试")
class AliyunEmbeddingConcurrencyLimiterTest {

    @Test
    @DisplayName("并发数为一时第二个请求必须等待第一个请求释放许可")
    void shouldLimitConcurrentRequests() throws Exception {
        AliyunEmbeddingConcurrencyLimiter limiter = createLimiter(1, 2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstRequestEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        CountDownLatch secondRequestEntered = new CountDownLatch(1);

        try {
            Future<String> first = executor.submit(() -> limiter.execute(() -> {
                firstRequestEntered.countDown();
                awaitLatch(releaseFirstRequest);
                return "first";
            }));
            assertTrue(firstRequestEntered.await(1, TimeUnit.SECONDS));

            Future<String> second = executor.submit(() -> limiter.execute(() -> {
                secondRequestEntered.countDown();
                return "second";
            }));

            // 第一个请求仍持有唯一许可时，第二个请求不能进入真正的接口调用阶段。
            assertFalse(secondRequestEntered.await(200, TimeUnit.MILLISECONDS));
            releaseFirstRequest.countDown();

            assertEquals("first", first.get(1, TimeUnit.SECONDS));
            assertEquals("second", second.get(1, TimeUnit.SECONDS));
            assertTrue(secondRequestEntered.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirstRequest.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("请求抛出异常后也必须归还并发许可")
    void shouldReleasePermitAfterRequestFailure() {
        AliyunEmbeddingConcurrencyLimiter limiter = createLimiter(1, 1);

        assertThrows(IllegalStateException.class, () -> limiter.execute(() -> {
            throw new IllegalStateException("模拟接口失败");
        }));

        // 如果上一次异常没有归还许可，这一次调用会等待到超时而不是立即成功。
        assertEquals("success", limiter.execute(() -> "success"));
    }

    // 创建测试所需的最小配置，避免依赖 Spring 容器和真实阿里云接口。
    private AliyunEmbeddingConcurrencyLimiter createLimiter(int maxConcurrency, int timeoutSeconds) {
        AliyunEmbeddingProperties properties = new AliyunEmbeddingProperties();
        properties.setMaxConcurrency(maxConcurrency);
        properties.setAcquireTimeoutSeconds(timeoutSeconds);
        return new AliyunEmbeddingConcurrencyLimiter(properties);
    }

    // 在模拟请求中等待测试线程发出释放信号，中断时恢复线程中断标记并让测试失败。
    private void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待被中断", exception);
        }
    }
}
