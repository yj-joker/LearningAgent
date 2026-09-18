package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.entity.DocumentTask;
import com.yjjoker.learningagent.repository.DocumentTaskRepository;
import com.yjjoker.learningagent.service.DocumentsParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

// 定时扫描并派发文档处理任务。
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentTaskScheduler {
    private static final int MAX_DISPATCH_PER_ROUND = 10;

    private final DocumentTaskRepository documentTaskRepository;
    private final DocumentsParseService documentsParseService;
    private final ThreadPoolTaskExecutor documentParseExecutor;

    // 每秒扫描一次待处理任务，并根据线程池剩余容量限制本轮派发数量。
    @Scheduled(fixedDelayString = "${document.task.scheduler.fixed-delay-ms:1000}")
    public void dispatchPendingTasks() {
        // 计算线程池剩余容量
        int availableCapacity = documentParseExecutor.getMaxPoolSize()
                + documentParseExecutor.getQueueCapacity()
                - documentParseExecutor.getActiveCount()
                - documentParseExecutor.getQueueSize();
        if (availableCapacity <= 0) {
            log.info("当前解析线程池已满，暂不派发任务");
            return;
        }

        // 计算本轮派发数量限制
        int limit = Math.min(MAX_DISPATCH_PER_ROUND, availableCapacity);
        // 查询待处理任务
        List<DocumentTask> tasks = documentTaskRepository.findPendingTasks(limit);
        // 遍历待处理任务
        for (DocumentTask task : tasks) {
            // 修改任务状态为正在处理，原子操作，防止任务被重复处理
            LocalDateTime now = LocalDateTime.now();
            if (documentTaskRepository.claimTask(task.getId(), now) != 1) {
                log.info("任务当前不处于待处理状态，跳过派发，taskId={}", task.getId());
                continue;
            }
            // 提交文档处理任务
            try {
                // 只传任务 ID；异步线程会重新查询任务和文档的最新状态。
                documentsParseService.parseDocuments(task.getId());
                log.info("提交文档处理任务成功，taskId={}", task.getId());
            } catch (RejectedExecutionException e) {
                // 提交文档处理任务被线程池拒绝，将任务重新加入队列
                documentTaskRepository.requeueTask(
                        task.getId(),
                        "当前解析线程池已满，任务稍后重试",
                        LocalDateTime.now()
                );
                log.warn("提交文档处理任务被线程池拒绝，taskId={}", task.getId(), e);
            }
        }
    }
}
