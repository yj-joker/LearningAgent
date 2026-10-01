package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 先做只读预检查，再合并同一范围的触发通知；不扫描已批准申请，也不持有请求连接。
@Component
@Slf4j
public class MemoryConsolidationScheduler {
    private final MemoryConsolidationService service;
    private final Executor executor;
    // 只防止当前进程重复生成；数据库唯一索引负责防止多个进程创建重复申请。
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    // 独立的小线程池不与文档解析争抢队列，也不会在线程池满时退回聊天线程执行。
    public MemoryConsolidationScheduler(MemoryConsolidationService service,
            @Qualifier("memoryConsolidationExecutor") Executor executor) {
        this.service = service;
        this.executor = executor;
    }

    // 实际记忆写入提交后才触发检查，回滚时不产生后台任务。
    public void requestAfterCommit(Long userId, Long sessionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                // 回调只读条件并提交任务；进度初始化和模型调用都留在后台。
                @Override
                public void afterCommit() { request(userId, sessionId); }
            });
        } else {
            request(userId, sessionId);
        }
    }

    // Hook 和审批提交回调共用入口；只检查已验证的身份，不在这里等待模型。
    public void request(Long userId, Long sessionId) {
        if (userId == null || userId <= 0 || sessionId == null || sessionId <= 0) {
            log.warn("跳过整理调度：缺少有效用户或会话");
            return;
        }
        for (MemoryScope scope : MemoryScope.values()) {
            schedule(userId, sessionId, scope);
        }
    }

    // 长期记忆按用户去重，会话记忆按会话去重；没达到条件就不占用线程池队列。
    private void schedule(Long userId, Long sessionId, MemoryScope scope) {
        String key = scope + ":" + (scope == MemoryScope.USER ? userId : sessionId);
        // 快速跳过已经排队的范围；后面的 add 才是防止并发重复登记的原子操作。
        if (inFlight.contains(key)) {
            log.debug("整理任务已排队或执行中，跳过预检查，scopeKey={}", key);
            return;
        }
        try {
            if (!service.shouldSchedule(userId, sessionId, scope)) {
                log.debug("整理预检查不满足条件，未进入线程池，scopeKey={}", key);
                return;
            }
        } catch (RuntimeException exception) {
            // 数据库预检查失败只跳过当前范围，不能影响已完成的回答或另一类记忆。
            log.warn("整理预检查失败，scopeKey={}，errorType={}，未提交任务", key, exception.getClass().getSimpleName());
            return;
        }
        if (!inFlight.add(key)) {
            log.debug("整理任务已排队或执行中，合并触发通知，scopeKey={}", key);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    service.consolidateScope(userId, sessionId, scope);
                } catch (RuntimeException exception) {
                    // 不清进度，不打印模型正文；下次触发可重试，不能影响已经完成的回答。
                    log.warn("后台整理未完成，scopeKey={}，errorType={}，保留进度", key, exception.getClass().getSimpleName());
                } finally {
                    // 标记覆盖排队和执行，直到方案保存结束才释放；不等待用户审批。
                    inFlight.remove(key);
                }
            });
            log.info("已提交整理检查，scopeKey={}", key);
        } catch (RejectedExecutionException exception) {
            // 队列满或服务关闭时释放占用，后续通知仍有机会重新提交。
            inFlight.remove(key);
            log.warn("整理队列暂不可用，scopeKey={}，保留进度等待下次触发", key);
        }
    }
}
