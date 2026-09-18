package com.yjjoker.learningagent.config;

import com.yjjoker.learningagent.repository.DocumentTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// 应用启动时恢复上一次运行中断的文档任务。
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentTaskRecoveryRunner implements ApplicationRunner {
    private final DocumentTaskRepository documentTaskRepository;

    // 启动阶段把遗留的 RUNNING 任务恢复为 PENDING，随后由调度器重新派发。
    @Override
    public void run(ApplicationArguments args) {
        // TODO [问题12] 多实例部署时不能无条件重置全部 RUNNING；其他实例可能仍在执行这些任务。
        int recovered = documentTaskRepository.resetRunningTasksToPending(LocalDateTime.now());
        log.info("应用启动完成，恢复文档处理任务数量={}", recovered);
    }
}
