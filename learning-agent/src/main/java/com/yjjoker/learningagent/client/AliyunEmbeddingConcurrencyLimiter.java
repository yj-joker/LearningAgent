package com.yjjoker.learningagent.client;

import com.yjjoker.learningagent.config.AliyunEmbeddingProperties;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Slf4j
//高并发限制器，防止一次性向阿里云请求过多导致embedding等服务错误
public class AliyunEmbeddingConcurrencyLimiter {
    private final Semaphore semaphore;
    private final int acquireTimeoutSeconds;

    // 根据配置创建当前 JVM 内共享的公平信号量，所有阿里云 Embedding 请求共用同一组许可。
    public AliyunEmbeddingConcurrencyLimiter(AliyunEmbeddingProperties properties) {
        int maxConcurrency = properties.getMaxConcurrency();
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("阿里云 Embedding 最大并发数必须大于 0");
        }
        if (properties.getAcquireTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("阿里云 Embedding 并发许可等待时间必须大于 0");
        }

        // true 表示按等待顺序相对公平地发放许可，避免某些解析线程长期拿不到调用机会。
        this.semaphore = new Semaphore(maxConcurrency, true);
        this.acquireTimeoutSeconds = properties.getAcquireTimeoutSeconds();

        // TODO 多实例部署时，本地 Semaphore 只能限制单个 JVM，需要改为 Redis 分布式信号量或基于 Redis 的全局限流器，并让所有实例共享同一并发与 TPM 配额。
        log.info("阿里云 Embedding 本地并发限制器初始化完成，maxConcurrency={}，acquireTimeoutSeconds={}",
                maxConcurrency, acquireTimeoutSeconds);
    }

    // 获取一个阿里云调用许可后执行请求，并保证成功、失败或抛出异常时都会归还许可。
    public <T> T execute(Supplier<T> request) {
        Objects.requireNonNull(request, "阿里云 Embedding 请求不能为空");
        boolean acquired = false;
        try {
            // 使用有限等待代替永久阻塞，避免阿里云持续拥塞时耗尽整个文档解析线程池。
            acquired = semaphore.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("等待阿里云 Embedding 并发许可超时，acquireTimeoutSeconds={}，waitingThreads={}",
                        acquireTimeoutSeconds, semaphore.getQueueLength());
                throw new LearningAgentServiceException("文本向量服务当前繁忙，请稍后重试");
            }

            // 只有成功取得许可的线程才能真正发送阿里云 HTTP 请求。
            return request.get();
        } catch (InterruptedException exception) {
            // 恢复中断标记，让应用关闭或任务取消流程能够继续识别当前线程已被中断。
            Thread.currentThread().interrupt();
            throw new LearningAgentServiceException("等待文本向量服务时任务被中断", exception);
        } finally {
            if (acquired) {
                // 许可必须放在 finally 中归还，否则一次异常就可能永久减少可用并发数。
                semaphore.release();
            }
        }
    }
}
