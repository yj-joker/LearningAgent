package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.harness.memory.model.MemoryApprovalRequest;
import com.yjjoker.learningagent.harness.memory.model.MemoryApprovalStatus;
import com.yjjoker.learningagent.harness.memory.model.MemoryCandidate;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionContext;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionTarget;
import com.yjjoker.learningagent.harness.memory.model.MemoryOperation;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import com.yjjoker.learningagent.harness.memory.model.MemoryWriteReceipt;
import com.yjjoker.learningagent.harness.memory.model.MemoryApprovalView;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.memory.service.ConversationMemoryService;
import com.yjjoker.learningagent.harness.memory.service.MemoryApprovalService;
import com.yjjoker.learningagent.harness.memory.service.MemoryCandidatePersistenceService;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.repository.MemoryApprovalRepository;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.Optional;
import java.time.LocalDateTime;

import static com.yjjoker.learningagent.harness.memory.MemoryTestData.USER_ID;
import static com.yjjoker.learningagent.harness.memory.MemoryTestData.SESSION_ID;
import static com.yjjoker.learningagent.harness.memory.model.MemoryOperation.CREATE;
import static com.yjjoker.learningagent.harness.memory.model.MemoryScope.USER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 不连接数据库，验证审批服务是否保存申请、批准时复用持久化入口、拒绝时不写记忆。
class MemoryApprovalServiceTest {
    private final MemoryApprovalRepository repository = mock(MemoryApprovalRepository.class);
    private final MemoryCandidatePersistenceService persistence = mock(MemoryCandidatePersistenceService.class);
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationApprovalService consolidation =
            mock(com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationApprovalService.class);
    private final com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationScheduler scheduler =
            mock(com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationScheduler.class);
    private final com.yjjoker.learningagent.notification.ApprovalNotifier notifier =
            mock(com.yjjoker.learningagent.notification.ApprovalNotifier.class);
    private final MemoryApprovalService service = new MemoryApprovalService(repository, persistence, sessions, consolidation, scheduler, notifier);

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        // 单元测试用同一个对象模拟加锁读取；真正的数据库锁由 Repository SQL 执行。
        when(repository.lock(anyLong(), eq(USER_ID))).thenAnswer(invocation ->
                repository.findById(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    // 创建申请只保存候选和目标快照，不调用真实记忆写入。
    @Test
    void shouldCreatePendingRequest() {
        MemoryCandidate candidate = candidate(CREATE, "请记住我喜欢篮球");
        MemoryExtractionContext context = new MemoryExtractionContext(USER_ID, SESSION_ID, List.of());
        doAnswer(invocation -> {
            MemoryApprovalRequest request = invocation.getArgument(0);
            request.setId(7L);
            return 1;
        }).when(repository).insert(any());

        MemoryApprovalRequest request = service.create(USER_ID, SESSION_ID, candidate, context);

        assertEquals(7L, request.getId());
        assertEquals(MemoryApprovalStatus.PENDING, request.getStatus());
        verify(repository).insert(any());
        verify(notifier).changedAfterCommit(USER_ID);
        verifyNoInteractions(persistence);
    }

    // 批准会重新恢复快照，再进入已有的事务写入服务。
    @Test
    void shouldApproveWithStoredSnapshot() {
        MemoryApprovalRequest request = pendingRequest();
        when(repository.findById(7L)).thenReturn(request);
        when(repository.decide(eq(7L), eq(USER_ID), eq(MemoryApprovalStatus.APPROVED), anyString(), any(), any()))
                .thenReturn(1);
        when(persistence.persistToolCandidate(any(), anyString(), any()))
                .thenReturn(new MemoryWriteReceipt(CREATE, USER, USER_ID, List.of(9L), List.of("sport")));

        var result = service.approve(7L);

        assertEquals(MemoryApprovalStatus.APPROVED, result.getStatus());
        verify(persistence).persistToolCandidate(any(MemoryExtractionContext.class),
                eq("请记住我喜欢篮球"), any(MemoryCandidate.class));
        // 自动提取的申请在聊天结束后批准，也会安排提交后的整理检查。
        verify(scheduler).requestAfterCommit(USER_ID, SESSION_ID);
        verify(notifier).changedAfterCommit(USER_ID);
    }

    // 拒绝只更新申请状态，不执行记忆写入。
    @Test
    void shouldRejectWithoutWritingMemory() {
        MemoryApprovalRequest request = pendingRequest();
        when(repository.findById(7L)).thenReturn(request);
        when(repository.decide(eq(7L), eq(USER_ID), eq(MemoryApprovalStatus.REJECTED), anyString(), any(), any()))
                .thenReturn(1);

        var result = service.reject(7L, "用户暂不确认");

        assertEquals(MemoryApprovalStatus.REJECTED, result.getStatus());
        verify(notifier).changedAfterCommit(USER_ID);
        verifyNoInteractions(persistence);
    }

    // 相同申请不能重复批准，防止重复修改或重复增加整理计数。
    @Test
    void shouldRejectSecondDecision() {
        MemoryApprovalRequest request = pendingRequest();
        request.setStatus(MemoryApprovalStatus.APPROVED);
        when(repository.findById(7L)).thenReturn(request);
        assertThrows(RuntimeException.class, () -> service.approve(7L));
        verifyNoInteractions(persistence);
    }

    // 更新和删除的申请会保存当时的版本字段，批准时可以恢复并交给后端快照校验。
    @Test
    void shouldRestoreTargetSnapshotBeforeApproval() {
        LocalDateTime updatedAt = LocalDateTime.now();
        MemoryExtractionTarget target = new MemoryExtractionTarget("memory_1", USER, USER_ID, 3L,
                "sport", "运动偏好", "喜欢篮球", updatedAt);
        MemoryCandidate candidate = candidate(MemoryOperation.UPDATE, "请修改运动偏好为足球");
        candidate.setTargetMemoryRefs(List.of("memory_1"));
        candidate.setMemorySummary("喜欢足球");
        candidate.setMemoryContent("喜欢足球");
        MemoryExtractionContext context = new MemoryExtractionContext(USER_ID, SESSION_ID, List.of(target));
        doAnswer(invocation -> {
            MemoryApprovalRequest request = invocation.getArgument(0);
            request.setId(12L);
            return 1;
        }).when(repository).insert(any());
        MemoryApprovalRequest created = service.create(USER_ID, SESSION_ID, candidate, context);
        when(repository.findById(12L)).thenReturn(created);
        when(repository.decide(eq(12L), eq(USER_ID), eq(MemoryApprovalStatus.APPROVED), anyString(), any(), any()))
                .thenReturn(1);
        when(persistence.persistToolCandidate(any(), anyString(), any()))
                .thenReturn(new MemoryWriteReceipt(MemoryOperation.UPDATE, USER, USER_ID, List.of(3L), List.of("sport")));

        service.approve(12L);

        verify(persistence).persistToolCandidate(argThat(saved -> saved.resolve("memory_1") != null),
                eq("请修改运动偏好为足球"), any(MemoryCandidate.class));
    }

    // 他人的申请在锁定或修改之前就拒绝，不能通过审批编号跨用户操作。
    @Test
    void shouldRejectAnotherUsersApproval() {
        MemoryApprovalRequest request = pendingRequest();
        request.setUserId(USER_ID + 1);
        when(repository.findById(7L)).thenReturn(request);
        assertThrows(RuntimeException.class, () -> service.approve(7L));
        verify(repository, never()).lock(any(), any());
        verifyNoInteractions(persistence);
    }

    // 加锁后必须重新确认状态，不能使用等待锁之前读到的旧 PENDING。
    @Test
    void shouldRecheckDecisionAfterLocking() {
        MemoryApprovalRequest request = pendingRequest();
        MemoryApprovalRequest decided = pendingRequest();
        decided.setStatus(MemoryApprovalStatus.REJECTED);
        when(repository.findById(7L)).thenReturn(request);
        when(repository.lock(7L, USER_ID)).thenReturn(decided);
        assertThrows(RuntimeException.class, () -> service.approve(7L));
        verifyNoInteractions(persistence);
    }

    // 构造独立待审批申请，不依赖真实数据库主键。
    private MemoryApprovalRequest pendingRequest() {
        MemoryApprovalRequest request = new MemoryApprovalRequest();
        request.setId(7L);
        request.setUserId(USER_ID);
        request.setSessionId(SESSION_ID);
        request.setOperation(CREATE);
        request.setScope(USER);
        request.setStatus(MemoryApprovalStatus.PENDING);
        MemoryCandidate candidate = candidate(CREATE, "请记住我喜欢篮球");
        try {
            request.setCandidateJson(new tools.jackson.databind.json.JsonMapper().writeValueAsString(candidate));
            request.setTargetSnapshotJson("[]");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return request;
    }

    // 准备合法候选，让各场景只改变审批或并发状态。
    private MemoryCandidate candidate(MemoryOperation operation, String evidence) {
        MemoryCandidate candidate = new MemoryCandidate();
        candidate.setOperation(operation);
        candidate.setScope(USER);
        candidate.setMemoryKey("sport");
        candidate.setMemoryTopic("运动偏好");
        candidate.setMemorySummary("喜欢篮球");
        candidate.setMemoryContent("喜欢篮球");
        candidate.setUserEvidence(evidence);
        return candidate;
    }
}
