package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.config.MemoryConsolidationProperties;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.*;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.*;
import com.yjjoker.learningagent.utils.BaseContext;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static com.yjjoker.learningagent.harness.memory.MemoryTestData.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryScope.USER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 使用真实方案序列化、审批服务和整理写入器；数据库和模型使用测试替身。
class MemoryConsolidationApprovalTest {
    private final MemoryApprovalRepository requests = mock(MemoryApprovalRepository.class);
    private final MemoryConsolidationRepository progress = mock(MemoryConsolidationRepository.class);
    private final StructuredMemoryService memories = mock(StructuredMemoryService.class);
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final MemoryConsolidator model = mock(MemoryConsolidator.class);
    private final MemoryConsolidationScheduler scheduler = mock(MemoryConsolidationScheduler.class);
    private final com.yjjoker.learningagent.notification.ApprovalNotifier notifier =
            mock(com.yjjoker.learningagent.notification.ApprovalNotifier.class);
    private final MemoryConsolidationProperties properties = new MemoryConsolidationProperties();
    private final List<MemoryApprovalRequest> saved = new ArrayList<>();
    private MemoryConsolidationService coordinator;
    private MemoryApprovalService approvals;
    private MemoryConsolidationState state;
    private UserMemory first;
    private UserMemory second;
    private MemoryConsolidationPlan proposed;

    // 每个测试使用独立记忆和申请，避免一次批准影响另一个场景。
    @BeforeEach
    void setup() {
        BaseContext.setCurrentId(USER_ID);
        first = user(1, "sport", "喜欢篮球");
        second = user(2, "weekend", "周末打篮球");
        var session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        state = new MemoryConsolidationState();
        state.setScope(USER);
        state.setOwnerId(USER_ID);
        state.setChangeCount(20);
        when(progress.find(USER, USER_ID)).thenReturn(state);
        when(progress.lock(USER, USER_ID)).thenReturn(state);
        when(progress.markProcessed(USER, USER_ID, 20, 0)).thenAnswer(call -> { state.setProcessedCount(20); return 1; });
        when(memories.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(first, second));
        when(memories.recallUserMemory(USER_ID, 1L)).thenReturn(first);
        when(memories.recallUserMemory(USER_ID, 2L)).thenReturn(second);
        when(memories.lockUserMemory(USER_ID, 1L)).thenReturn(first);
        when(memories.lockUserMemory(USER_ID, 2L)).thenReturn(second);
        proposed = plan();
        when(model.consolidate(any())).thenReturn(proposed);

        // 用内存申请列表模拟查询和状态条件；真实唯一索引来自统一建表文件。
        when(requests.insert(any())).thenAnswer(call -> {
            MemoryApprovalRequest request = call.getArgument(0);
            request.setId((long) saved.size() + 1);
            saved.add(request);
            return 1;
        });
        when(requests.findById(anyLong())).thenAnswer(call -> saved.stream()
                .filter(r -> Objects.equals(r.getId(), call.getArgument(0))).findFirst().orElse(null));
        when(requests.lock(anyLong(), eq(USER_ID))).thenAnswer(call -> requests.findById(call.getArgument(0)));
        when(requests.decide(anyLong(), eq(USER_ID), any(), anyString(), any(), any())).thenReturn(1);
        when(requests.countBlockingConsolidations(any(), anyLong(), anyLong(), anyLong())).thenAnswer(call ->
                (int) saved.stream().filter(r -> r.getScope() == call.getArgument(0)
                        && (r.getStatus() == MemoryApprovalStatus.PENDING
                        || (Objects.equals(r.getSnapshotChangeCount(), call.<Long>getArgument(2))
                        && Objects.equals(r.getSnapshotProcessedCount(), call.<Long>getArgument(3))))).count());
        var writer = new MemoryConsolidationPersistenceService(memories, progress);
        var proposalService = new MemoryConsolidationApprovalService(requests, sessions, writer, notifier);
        coordinator = new MemoryConsolidationService(properties, progress, memories, model, writer, proposalService);
        approvals = new MemoryApprovalService(requests, mock(MemoryCandidatePersistenceService.class), sessions, proposalService, scheduler, notifier);
    }

    // 清除登录身份，避免污染其他单元测试。
    @AfterEach
    void cleanup() { BaseContext.removeCurrentId(); }

    // 未达阈值只读进度，不能加载记忆正文、查询审批或请求模型。
    @Test
    void precheckSkipsBelowThresholdWithoutLoadingMemories() {
        state.setChangeCount(39);
        state.setProcessedCount(20);
        assertFalse(coordinator.shouldSchedule(USER_ID, SESSION_ID, USER));
        verifyNoInteractions(memories, model, requests);
        verify(progress, never()).initialize(any(), anyLong(), anyLong());
    }

    // 达到阈值仅代表可以排队，预检查本身不生成方案也不写审批。
    @Test
    void eligiblePrecheckStillDoesNotGenerateOrPersist() {
        assertTrue(coordinator.shouldSchedule(USER_ID, SESSION_ID, USER));
        verifyNoInteractions(memories, model);
        verify(requests, never()).insert(any());
        verify(progress, never()).initialize(any(), anyLong(), anyLong());
        verify(progress, never()).markProcessed(any(), anyLong(), anyLong(), anyLong());
    }

    // 首次没有进度时仅 COUNT；即使数量达标也不能在提交后回调里初始化数据库。
    @Test
    void firstPrecheckCountsWithoutInitializingProgress() {
        when(progress.find(USER, USER_ID)).thenReturn(null);
        when(progress.countActiveUserMemories(USER_ID)).thenReturn(19L, 20L);
        assertFalse(coordinator.shouldSchedule(USER_ID, SESSION_ID, USER));
        assertTrue(coordinator.shouldSchedule(USER_ID, SESSION_ID, USER));
        verifyNoInteractions(memories, model);
        verify(progress, never()).initialize(any(), anyLong(), anyLong());
        verify(progress, never()).countActiveSessionMemories(anyLong());
    }

    // USER 与 SESSION 的首次统计不能串用归属或表。
    @Test
    void firstSessionPrecheckUsesSessionCount() {
        when(progress.countActiveSessionMemories(SESSION_ID)).thenReturn(20L);
        assertTrue(coordinator.shouldSchedule(USER_ID, SESSION_ID, MemoryScope.SESSION));
        verify(progress).countActiveSessionMemories(SESSION_ID);
        verify(progress, never()).countActiveUserMemories(anyLong());
        verifyNoInteractions(memories, model);
    }

    // 开关关闭时连预检查查询也不执行。
    @Test
    void disabledPrecheckDoesNotQueryDatabase() {
        properties.setEnabled(false);
        assertFalse(coordinator.shouldSchedule(USER_ID, SESSION_ID, USER));
        verifyNoInteractions(progress, memories, requests, model);
    }

    // 等待审批时由数据库状态拦截，不需要一直占住内存去重标记。
    @Test
    void existingProposalIsSkippedBeforeQueueing() {
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        var queued = new ArrayList<Runnable>();
        var dispatcher = new MemoryConsolidationScheduler(coordinator, queued::add);
        clearInvocations(model, memories);
        dispatcher.request(USER_ID, SESSION_ID);
        assertTrue(queued.isEmpty());
        verifyNoInteractions(model, memories);
        assertEquals(1, saved.size());
        // 后台生成提案的入口同样登记通知，而不是依赖聊天 HTTP 响应。
        verify(notifier).changedAfterCommit(USER_ID);
    }

    // 排队期间进度已经完成，后台必须复查并跳过，不能沿用入队前的结论。
    @Test
    void workerRechecksProgressAfterQueueing() {
        var queued = new ArrayList<Runnable>();
        var dispatcher = new MemoryConsolidationScheduler(coordinator, queued::add);
        dispatcher.request(USER_ID, SESSION_ID);
        assertEquals(1, queued.size());
        state.setProcessedCount(20);
        queued.getFirst().run();
        verifyNoInteractions(model, memories);
        assertTrue(saved.isEmpty());
        // 复查后跳过也必须释放标记，后续新变更可以再次入队。
        state.setChangeCount(40);
        dispatcher.request(USER_ID, SESSION_ID);
        assertEquals(2, queued.size());
    }

    // 排队后另一个任务先保存了申请，当前工作线程不得重复请求模型。
    @Test
    void workerRechecksProposalsAfterQueueing() {
        var queued = new ArrayList<Runnable>();
        var dispatcher = new MemoryConsolidationScheduler(coordinator, queued::add);
        dispatcher.request(USER_ID, SESSION_ID);
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        clearInvocations(model, memories);
        queued.getFirst().run();
        verifyNoInteractions(model, memories);
        assertEquals(1, saved.size());
    }

    // 即使未开启 MySQL 集成测试，也要检查统一建表文件能提供全部测试表。
    @Test
    void loadsAllMemoryTablesFromUnifiedSchema() throws Exception {
        for (String table : List.of("learning_sessions", "user_memories", "session_memories",
                "memory_consolidation_state", "memory_approval_requests")) {
            assertTrue(tableDdl(table).startsWith("CREATE TABLE IF NOT EXISTS " + table));
        }
        String approvalsDdl = tableDdl("memory_approval_requests");
        assertTrue(approvalsDdl.contains("uk_pending_consolidation"));
        assertTrue(approvalsDdl.contains("uk_consolidation_version"));
    }

    // 后台只创建提案；批准时使用已保存 JSON，不使用后来被修改的模型对象。
    @Test
    void waitsForApprovalThenAppliesExactlyStoredPlanOnce() {
        BaseContext.removeCurrentId();
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        var request = saved.getFirst();
        assertEquals(MemoryApprovalType.CONSOLIDATION, request.getApprovalType());
        assertNull(request.getOperation());
        assertEquals(MemoryApprovalStatus.PENDING, request.getStatus());
        assertEquals("喜欢篮球", first.getMemoryContent());
        verify(memories, never()).updateUserMemory(any());
        verify(progress, never()).markProcessed(any(), any(), anyLong(), anyLong());
        proposed.getMerges().getFirst().setMemoryContent("这不是用户批准的方案");

        BaseContext.setCurrentId(USER_ID);
        var result = approvals.approve(request.getId());
        assertEquals(MemoryApprovalStatus.APPROVED, result.getStatus());
        assertEquals("喜欢篮球，周末经常打篮球", first.getMemoryContent());
        verify(memories).deleteUserMemory(USER_ID, 2L);
        assertEquals(20, state.getProcessedCount());
        assertThrows(RuntimeException.class, () -> approvals.approve(request.getId()));
        verify(memories, times(1)).updateUserMemory(first);
        verify(model, times(1)).consolidate(any());
        verifyNoInteractions(scheduler);
    }

    // 待审批时即使又有变更，也不并行创建第二份方案；重复触发不重复请求模型。
    @Test
    void doesNotRegenerateWhileAnotherProposalIsPending() {
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        state.setChangeCount(21);
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        assertEquals(1, saved.size());
        verify(model, times(1)).consolidate(any());
    }

    // 拒绝不推进成功进度；同一版本不再申请，有新变更后才允许重新判断。
    @Test
    void remembersRejectedVersionWithoutChangingMemoryOrProgress() {
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        approvals.reject(saved.getFirst().getId(), "暂不合并");
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        verify(model, times(1)).consolidate(any());
        assertEquals(0, state.getProcessedCount());
        verify(memories, never()).updateUserMemory(any());
        state.setChangeCount(21);
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        verify(model, times(2)).consolidate(any());
        assertEquals(2, saved.size());
    }

    // 等待期间正文变了，即使其他字段没变，也关闭旧申请而不覆盖新内容。
    @Test
    void marksChangedMemoryAsStaleWithoutAnyWrite() {
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        second.setMemoryContent("用户现在喜欢羽毛球");
        assertEquals(MemoryApprovalStatus.STALE, approvals.approve(saved.getFirst().getId()).getStatus());
        verify(memories, never()).updateUserMemory(any());
        verify(memories, never()).deleteUserMemory(any(), any());
        assertEquals(0, state.getProcessedCount());
    }

    // 等待期间新增了记忆，也不能把未检查的新变更一起标成已整理。
    @Test
    void marksChangedProgressAsStale() {
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        state.setChangeCount(21);
        assertEquals(MemoryApprovalStatus.STALE, approvals.approve(saved.getFirst().getId()).getStatus());
        verify(memories, never()).updateUserMemory(any());
        assertEquals(0, state.getProcessedCount());
    }

    // 他人的申请必须在业务写入前拒绝；后台携带身份不代表浏览器可以指定身份。
    @Test
    void refusesForeignApproval() {
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        BaseContext.setCurrentId(USER_ID + 1);
        assertThrows(RuntimeException.class, () -> approvals.approve(saved.getFirst().getId()));
        verify(memories, never()).lockUserMemory(any(), any());
    }

    // 没有合并时不创建空审批，只按原快照确认这次检查进度。
    @Test
    void completesNoChangeInspectionWithoutApproval() {
        when(model.consolidate(any())).thenReturn(new MemoryConsolidationPlan());
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        assertTrue(saved.isEmpty());
        assertEquals(20, state.getProcessedCount());
        verify(memories, never()).updateUserMemory(any());
    }

    // 调度器直接调用按范围入口时也必须遵守关闭开关。
    @Test
    void honorsDisabledFlagAtWorkerEntry() {
        properties.setEnabled(false);
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        verifyNoInteractions(model);
        verify(progress, never()).find(any(), any());
    }

    // 真正通过 Spring 事务拦截器检查：申请状态保存失败必须要求连接回滚。
    @Test
    void rollsBackWhenApprovalStatusCannotBeSaved() throws Exception {
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        when(requests.decide(anyLong(), anyLong(), any(), anyString(), any(), any())).thenReturn(0);
        Connection connection = mock(Connection.class);
        assertThrows(RuntimeException.class, () -> transactional(connection).approve(saved.getFirst().getId()));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    // 快照变化属于可记录的业务结果，不应把 STALE 状态一起回滚。
    @Test
    void commitsStaleDecisionWithoutBusinessWrites() throws Exception {
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        second.setMemoryContent("新内容");
        Connection connection = mock(Connection.class);
        assertEquals(MemoryApprovalStatus.STALE, transactional(connection).approve(saved.getFirst().getId()).getStatus());
        verify(connection).commit();
        verify(connection, never()).rollback();
        verify(memories, never()).updateUserMemory(any());
    }

    // 仓库写入异常不保存成功进度，也不能返回等待审批成功。
    @Test
    void leavesProgressWhenProposalInsertFails() {
        doThrow(new IllegalStateException("模拟保存失败")).when(requests).insert(any());
        assertThrows(RuntimeException.class, () -> coordinator.consolidateScope(USER_ID, SESSION_ID, USER));
        assertEquals(0, state.getProcessedCount());
        verify(memories, never()).updateUserMemory(any());
    }

    // 会话整理只能改会话记忆，即使两张表恰好存在相同主键也不能混用。
    @Test
    void approvesSessionPlanWithoutTouchingUserMemory() {
        var a = session(1, "goal", "周末学习集合");
        var b = session(2, "schedule", "周六学习集合");
        var sessionState = new MemoryConsolidationState();
        sessionState.setScope(MemoryScope.SESSION);
        sessionState.setOwnerId(SESSION_ID);
        sessionState.setChangeCount(20);
        when(progress.find(MemoryScope.SESSION, SESSION_ID)).thenReturn(sessionState);
        when(progress.lock(MemoryScope.SESSION, SESSION_ID)).thenReturn(sessionState);
        when(progress.markProcessed(MemoryScope.SESSION, SESSION_ID, 20, 0)).thenReturn(1);
        when(memories.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of(a, b));
        when(memories.recallSessionMemory(SESSION_ID, 1L)).thenReturn(a);
        when(memories.recallSessionMemory(SESSION_ID, 2L)).thenReturn(b);
        when(memories.lockSessionMemory(SESSION_ID, 1L)).thenReturn(a);
        when(memories.lockSessionMemory(SESSION_ID, 2L)).thenReturn(b);
        proposed.getMerges().getFirst().setMemoryContent("周六学习集合");
        coordinator.consolidateScope(USER_ID, SESSION_ID, MemoryScope.SESSION);
        assertEquals(MemoryScope.SESSION, saved.getFirst().getScope());
        assertEquals(MemoryApprovalStatus.APPROVED, approvals.approve(saved.getFirst().getId()).getStatus());
        verify(memories).updateSessionMemory(a);
        verify(memories).deleteSessionMemory(SESSION_ID, 2L);
        verify(memories, never()).updateUserMemory(any());
        verify(memories, never()).lockUserMemory(any(), any());
    }

    // 损坏的服务端快照不能当成空方案执行，也不能标为批准成功。
    @Test
    void rejectsDamagedSnapshotBeforeApplyingPlan() {
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        saved.getFirst().setTargetSnapshotJson("{}");
        assertThrows(IllegalArgumentException.class, () -> approvals.approve(saved.getFirst().getId()));
        assertEquals(MemoryApprovalStatus.PENDING, saved.getFirst().getStatus());
        verify(memories, never()).lockUserMemory(any(), any());
    }

    // 申请上的版本必须与 JSON 快照一致，不能替换快照后借用原批准。
    @Test
    void refusesMismatchedProposalVersion() {
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        saved.getFirst().setSnapshotChangeCount(99L);
        assertThrows(IllegalArgumentException.class, () -> approvals.approve(saved.getFirst().getId()));
        verify(memories, never()).lockUserMemory(any(), any());
    }

    // 使用两条同义记忆组成合法合并组，不依赖外部模型返回。
    private MemoryConsolidationPlan plan() {
        var merge = new MemoryMergeCandidate();
        merge.setKeepRef("memory_1");
        merge.setSourceRefs(List.of("memory_1", "memory_2"));
        merge.setMemoryTopic("运动偏好");
        merge.setMemorySummary("喜欢篮球");
        merge.setMemoryContent("喜欢篮球，周末经常打篮球");
        var plan = new MemoryConsolidationPlan();
        plan.setMerges(List.of(merge));
        return plan;
    }

    // 连接是测试替身；验证事务边界，不冒充已经跑过真实 MySQL。
    private MemoryApprovalService transactional(Connection connection) throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var factory = new ProxyFactory(approvals);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        return (MemoryApprovalService) factory.getProxy();
    }
}
