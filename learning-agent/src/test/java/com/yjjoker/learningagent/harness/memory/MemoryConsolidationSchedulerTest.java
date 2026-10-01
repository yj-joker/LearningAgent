package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.config.MemoryConsolidationProperties;
import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.hook.MemoryConsolidationHook;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import com.yjjoker.learningagent.harness.memory.service.*;
import com.yjjoker.learningagent.repository.*;
import com.yjjoker.learningagent.utils.BaseContext;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 手动推进后台队列，不 sleep、不连接模型，让并发边界测试稳定可重复。
class MemoryConsolidationSchedulerTest {
    private final MemoryConsolidationService service = mock(MemoryConsolidationService.class);
    private final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>());
    private final MemoryConsolidationScheduler scheduler = new MemoryConsolidationScheduler(service, tasks::add);

    // 默认两类范围都可整理；需要跳过的场景单独覆盖，队列仍由测试手动推进。
    @BeforeEach
    void readyByDefault() {
        when(service.shouldSchedule(anyLong(), anyLong(), any())).thenReturn(true);
    }

    // 同一用户的长期记忆共用一次检查，不同会话各自检查会话记忆。
    @Test
    void deduplicatesByOwnerAndRunsOnlyInWorker() {
        scheduler.request(20L, 10L);
        scheduler.request(20L, 10L);
        scheduler.request(20L, 11L);
        assertEquals(3, tasks.size());
        // 预检查在调用线程完成，真正整理必须等后台任务开始才执行。
        verify(service, times(3)).shouldSchedule(anyLong(), anyLong(), any());
        verify(service, never()).consolidateScope(anyLong(), anyLong(), any());
        tasks.forEach(Runnable::run);
        verify(service).consolidateScope(20L, 10L, MemoryScope.USER);
        verify(service).consolidateScope(20L, 10L, MemoryScope.SESSION);
        verify(service).consolidateScope(20L, 11L, MemoryScope.SESSION);
        scheduler.request(20L, 10L);
        assertEquals(5, tasks.size(), "完成后释放进程内占用，后续通知能重新检查");
    }

    // 后台身份来自提交参数；请求线程清理后仍能定位正确范围。
    @Test
    void doesNotDependOnRequestThreadLocal() {
        try {
            BaseContext.setCurrentId(20L);
            scheduler.request(20L, 10L);
            BaseContext.removeCurrentId();
            doAnswer(call -> { assertNull(BaseContext.getCurrentId()); return null; })
                    .when(service).consolidateScope(anyLong(), anyLong(), any());
            tasks.forEach(Runnable::run);
            verify(service).consolidateScope(20L, 10L, MemoryScope.USER);
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    // 一类整理失败不能占住范围或阻止另一类执行，也不向已完成的聊天抛异常。
    @Test
    void releasesScopeAfterFailure() {
        doThrow(new IllegalStateException("模拟失败")).when(service).consolidateScope(20L, 10L, MemoryScope.USER);
        scheduler.request(20L, 10L);
        assertDoesNotThrow(() -> tasks.forEach(Runnable::run));
        verify(service).consolidateScope(20L, 10L, MemoryScope.SESSION);
        scheduler.request(20L, 10L);
        assertEquals(4, tasks.size());
    }

    // 队列满时不能改为在调用线程请求模型；占用必须释放，允许下次再提交。
    @Test
    void dropsRejectedSubmissionWithoutRunningOnCaller() {
        AtomicInteger submissions = new AtomicInteger();
        Executor rejected = command -> { submissions.incrementAndGet(); throw new RejectedExecutionException(); };
        var rejecting = new MemoryConsolidationScheduler(service, rejected);
        assertDoesNotThrow(() -> rejecting.request(20L, 10L));
        assertDoesNotThrow(() -> rejecting.request(20L, 10L));
        assertEquals(4, submissions.get());
        verify(service, never()).consolidateScope(anyLong(), anyLong(), any());
    }

    // 提案写入还没提交时不安排任务；只有真实提交成功后才进队列。
    @Test
    void schedulesOnlyAfterCommit() throws Exception {
        transaction().executeWithoutResult(status -> {
            scheduler.requestAfterCommit(20L, 10L);
            assertTrue(tasks.isEmpty());
            // 连只读预检查也要等提交，不能使用还没生效的记忆变更。
            verifyNoInteractions(service);
        });
        assertEquals(2, tasks.size());
    }

    // 审批事务回滚后没有实际记忆变更，不应该触发整理。
    @Test
    void doesNotScheduleAfterRollback() throws Exception {
        transaction().executeWithoutResult(status -> {
            scheduler.requestAfterCommit(20L, 10L);
            status.setRollbackOnly();
        });
        assertTrue(tasks.isEmpty());
        verifyNoInteractions(service);
    }

    // 条件不满足时完全不入队；之后达到阈值仍能提交，不能留下错误的占用标记。
    @Test
    void skipsIneligibleScopesBeforeQueueing() {
        when(service.shouldSchedule(anyLong(), anyLong(), any())).thenReturn(false);
        scheduler.request(20L, 10L);
        assertTrue(tasks.isEmpty());
        verify(service, never()).consolidateScope(anyLong(), anyLong(), any());
        when(service.shouldSchedule(20L, 10L, MemoryScope.USER)).thenReturn(true);
        scheduler.request(20L, 10L);
        assertEquals(1, tasks.size());
        tasks.getFirst().run();
        verify(service).consolidateScope(20L, 10L, MemoryScope.USER);
        verify(service, never()).consolidateScope(20L, 10L, MemoryScope.SESSION);
    }

    // 预检查数据库失败时跳过当前范围，不阻止会话范围，也不占住下次通知。
    @Test
    void isolatesPrecheckFailureAndAllowsLaterRetry() {
        when(service.shouldSchedule(20L, 10L, MemoryScope.USER)).thenThrow(new IllegalStateException("数据库暂不可用"));
        assertDoesNotThrow(() -> scheduler.request(20L, 10L));
        assertEquals(1, tasks.size());
        // 覆盖会抛异常的测试替身时用 doReturn，避免配置阶段再次触发旧异常。
        doReturn(true).when(service).shouldSchedule(20L, 10L, MemoryScope.USER);
        scheduler.request(20L, 10L);
        assertEquals(2, tasks.size());
        tasks.forEach(Runnable::run);
        verify(service).consolidateScope(20L, 10L, MemoryScope.USER);
        verify(service).consolidateScope(20L, 10L, MemoryScope.SESSION);
    }

    // 两个请求同时通过预检查时，必须仍由原子 add 保证只入队一次。
    @Test
    void atomicallyDeduplicatesAfterConcurrentPrechecks() throws Exception {
        var bothChecked = new CyclicBarrier(2);
        when(service.shouldSchedule(20L, 10L, MemoryScope.USER)).thenAnswer(call -> {
            // 让两个请求都停在预检查中，复现“先判断、后登记”的并发窗口。
            bothChecked.await(5, TimeUnit.SECONDS);
            return true;
        });
        when(service.shouldSchedule(20L, 10L, MemoryScope.SESSION)).thenReturn(false);
        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(() -> scheduler.request(20L, 10L));
            var second = callers.submit(() -> scheduler.request(20L, 10L));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, tasks.size());
            tasks.getFirst().run();
            verify(service).consolidateScope(20L, 10L, MemoryScope.USER);
        } finally {
            callers.shutdownNow();
        }
    }

    // 工作线程开始后标记仍不能释放；执行过程中重复通知也不应产生另一个任务。
    @Test
    void keepsOwnershipUntilWorkerFinishes() {
        doAnswer(call -> {
            scheduler.request(20L, 10L);
            assertEquals(2, tasks.size());
            return null;
        }).when(service).consolidateScope(20L, 10L, MemoryScope.USER);
        scheduler.request(20L, 10L);
        tasks.getFirst().run();
        assertEquals(2, tasks.size());
        // USER 已完成可再次提交；SESSION 还在队列中，仍要合并重复通知。
        scheduler.request(20L, 10L);
        assertEquals(3, tasks.size());
    }

    // afterRun 会在多种结束状态触发，Hook 必须只处理正常完成，不能把暂停当完成。
    @Test
    void hookIgnoresWaitingAndFailedRuns() {
        var target = mock(MemoryConsolidationScheduler.class);
        var hook = new MemoryConsolidationHook(target);
        AgentRunContext waiting = context();
        waiting.markWaitingApproval();
        hook.afterRun(waiting);
        AgentRunContext failed = context();
        failed.markFailed(new IllegalStateException("失败"));
        hook.afterRun(failed);
        verifyNoInteractions(target);
        AgentRunContext completed = context();
        completed.markSucceeded();
        hook.afterRun(completed);
        verify(target).request(20L, 10L);
    }

    // 真实让 Spring 组装新依赖，避免仅手工 new 的单测漏掉循环依赖或线程池注入错误。
    @Test
    void wiresApprovalAndSchedulerWithoutCircularDependencies() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("memoryConsolidationExecutor", Executor.class, () -> Runnable::run);
            // 通知使用独立执行器；真实装配通知服务，继续检查新增依赖是否形成环。
            context.registerBean("approvalNotificationExecutor", Executor.class, () -> Runnable::run);
            context.register(com.yjjoker.learningagent.notification.ApprovalNotifier.class,
                    com.yjjoker.learningagent.notification.ApprovalWebSocketHandler.class);
            context.registerBean(MemoryConsolidationProperties.class);
            context.registerBean(MemoryApprovalRepository.class, () -> mock(MemoryApprovalRepository.class));
            context.registerBean(MemoryConsolidationRepository.class, () -> mock(MemoryConsolidationRepository.class));
            context.registerBean(LearningSessionRepository.class, () -> mock(LearningSessionRepository.class));
            context.registerBean(StructuredMemoryService.class, () -> mock(StructuredMemoryService.class));
            context.registerBean(MemoryConsolidator.class, () -> mock(MemoryConsolidator.class));
            context.registerBean(MemoryCandidatePersistenceService.class, () -> mock(MemoryCandidatePersistenceService.class));
            context.register(MemoryConsolidationPersistenceService.class, MemoryConsolidationApprovalService.class,
                    MemoryConsolidationService.class, MemoryConsolidationScheduler.class, MemoryApprovalService.class, MemoryConsolidationHook.class);
            context.refresh();
            assertNotNull(context.getBean(MemoryConsolidationHook.class));
            assertEquals(1, context.getBeansOfType(MemoryApprovalService.class).size());
        }
    }

    // 创建带有已验证归属的任务上下文。
    private AgentRunContext context() {
        AgentRunContext context = new AgentRunContext();
        context.bindSession(20L, 10L);
        return context;
    }

    // 只模拟 JDBC 提交和回滚，使用真正的 Spring 提交后回调机制。
    private TransactionTemplate transaction() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
