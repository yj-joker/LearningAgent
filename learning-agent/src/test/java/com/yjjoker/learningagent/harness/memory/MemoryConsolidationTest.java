package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.config.MemoryConsolidationProperties;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.memory.impl.LlmMemoryConsolidator;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.*;
import com.yjjoker.learningagent.harness.prompt.MemoryConsolidationPrompt;
import com.yjjoker.learningagent.repository.MemoryConsolidationRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static com.yjjoker.learningagent.harness.memory.MemoryTestData.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryScope.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 集中验证整理规则，不连接真实数据库或外部模型。
class MemoryConsolidationTest {
    private StructuredMemoryService memories;
    private MemoryConsolidationRepository progress;
    private MemoryConsolidationProperties properties;
    private MemoryConsolidator model;
    private MemoryConsolidationPersistenceService writer;
    private UserMemory first;
    private UserMemory second;

    // 每个测试使用独立记录和进度，避免修改互相影响。
    @BeforeEach
    void setUp() {
        memories = mock(StructuredMemoryService.class);
        progress = mock(MemoryConsolidationRepository.class);
        properties = new MemoryConsolidationProperties();
        model = mock(MemoryConsolidator.class);
        writer = new MemoryConsolidationPersistenceService(memories, progress);
        first = user(1, "favoriteSport", "最喜欢篮球，每周打三次。");
        second = user(2, "userFavoriteSport", "最喜欢篮球，通常周六和朋友一起打。");
        when(memories.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(first, second));
        when(memories.recallUserMemory(USER_ID, 1L)).thenReturn(first);
        when(memories.recallUserMemory(USER_ID, 2L)).thenReturn(second);
        when(memories.lockUserMemory(USER_ID, 1L)).thenReturn(first);
        when(memories.lockUserMemory(USER_ID, 2L)).thenReturn(second);
        when(progress.find(USER, USER_ID)).thenReturn(state(USER, 20, 0));
        when(progress.find(SESSION, SESSION_ID)).thenReturn(state(SESSION, 0, 0));
        when(progress.lock(USER, USER_ID)).thenReturn(state(USER, 20, 0));
        when(progress.markProcessed(USER, USER_ID, 20, 0)).thenReturn(1);
    }

    // 合并互补细节，保留主记录的 key，并软删除另一条记录。
    @Test
    void shouldMergeAndPreserveOriginalKeyWithoutCountingItsOwnChanges() {
        String oldKey = first.getMemoryKey();
        assertTrue(writer.persist(snapshot(), mergePlan()));
        assertEquals(oldKey, first.getMemoryKey());
        assertTrue(first.getMemoryContent().contains("三次"));
        assertTrue(first.getMemoryContent().contains("周六"));
        verify(memories).deleteUserMemory(USER_ID, 2L);
        verify(memories, never()).deleteUserMemory(USER_ID, 1L);
        verify(progress).markProcessed(USER, USER_ID, 20, 0);
        verify(progress, never()).addChanges(any(), any(), anyLong());
    }

    // 明确冲突只报告，不修改或删除原始事实。
    @Test
    void shouldKeepConflictsAndFinishThisInspection() {
        MemoryConsolidationPlan plan = new MemoryConsolidationPlan();
        plan.setConflicts(List.of(List.of("memory_1", "memory_2")));
        assertTrue(writer.persist(snapshot(), plan));
        verify(memories, never()).updateUserMemory(any());
        verify(memories, never()).deleteUserMemory(any(), any());
        verify(progress).markProcessed(USER, USER_ID, 20, 0);
    }

    // 正文变化即使没有改变摘要或更新时间，也应拒绝旧方案。
    @Test
    void shouldRejectChangedContentBeforeAnyWrite() {
        var snapshot = snapshot();
        second.setMemoryContent("用户已经明确改为最喜欢足球。");
        assertFalse(writer.persist(snapshot, mergePlan()));
        verify(memories, never()).updateUserMemory(any());
        verify(progress, never()).markProcessed(any(), any(), anyLong(), anyLong());
    }

    // 其他请求新增了记忆时，不能把新变更一起标成已整理。
    @Test
    void shouldRejectProgressChangedWhileModelWasRunning() {
        when(progress.lock(USER, USER_ID)).thenReturn(state(USER, 21, 0));
        assertFalse(writer.persist(snapshot(), mergePlan()));
        verify(memories, never()).updateUserMemory(any());
        verify(progress, never()).markProcessed(any(), any(), anyLong(), anyLong());
    }

    // 另一请求已经提交同一批整理时，不再次执行合并。
    @Test
    void shouldSkipAlreadyProcessedSnapshot() {
        when(progress.lock(USER, USER_ID)).thenReturn(state(USER, 20, 20));
        assertFalse(writer.persist(snapshot(), mergePlan()));
        verify(memories, never()).deleteUserMemory(any(), any());
    }

    // 引用越界时在读取数据库前拒绝，而不是尝试猜测 ID。
    @Test
    void shouldRejectUnknownReferenceBeforeDatabaseAccess() {
        MemoryConsolidationPlan plan = mergePlan();
        plan.getMerges().getFirst().setSourceRefs(List.of("memory_1", "memory_999"));
        assertThrows(MemoryConsolidationFormatException.class, () -> writer.persist(snapshot(), plan));
        verify(memories, never()).lockUserMemory(any(), any());
    }

    // 同一引用不能既准备删除又被当成未解决冲突保留。
    @Test
    void shouldRejectOverlapBetweenMergeAndConflict() {
        var plan = mergePlan();
        plan.setConflicts(List.of(List.of("memory_1", "memory_2")));
        assertThrows(MemoryConsolidationFormatException.class,
                () -> MemoryConsolidationValidator.validate(snapshot(), plan));
    }

    // 同一份错误输出中的单元素组和跨组重复，应在一次反馈里全部指出。
    @Test
    void shouldReportSingletonAndOverlapTogetherWithPaths() {
        var plan = mergePlan();
        plan.getMerges().getFirst().setSourceRefs(List.of("memory_1"));
        plan.setConflicts(List.of(List.of("memory_1", "memory_2")));
        var failure = assertThrows(MemoryConsolidationFormatException.class,
                () -> MemoryConsolidationValidator.validate(snapshot(), plan));
        assertTrue(failure.getMessage().contains("merges[0].sourceRefs：每组至少需要两个记忆引用"));
        assertTrue(failure.getMessage().contains("conflicts[0][0]：记忆引用不能重复或跨组合并"));
        assertTrue(failure.getMessage().contains("首次出现于 merges[0].sourceRefs[0]"));
    }

    // keepRef 必须在本组合并来源内，这种指向不能被误判成重复分组。
    @Test
    void shouldAllowKeepRefWithinItsOwnSources() {
        assertDoesNotThrow(() -> MemoryConsolidationValidator.validate(snapshot(), mergePlan()));
    }

    // 同组重复也应指出两个位置，不能用同一个引用凑够两条记忆。
    @Test
    void shouldLocateDuplicateWithinOneGroup() {
        var plan = mergePlan();
        plan.getMerges().getFirst().setSourceRefs(List.of("memory_1", "memory_1"));
        var failure = assertThrows(MemoryConsolidationFormatException.class,
                () -> MemoryConsolidationValidator.validate(snapshot(), plan));
        assertTrue(failure.getMessage().contains("merges[0].sourceRefs[1]"));
        assertTrue(failure.getMessage().contains("首次出现于 merges[0].sourceRefs[0]"));
    }

    // 没有操作也合法；省略的独立记录由后端保留，不需要单元素组。
    @Test
    void shouldAcceptEmptyOperationList() {
        assertDoesNotThrow(() -> MemoryConsolidationValidator.validate(snapshot(), new MemoryConsolidationPlan()));
    }

    // 错误很多时仍整份拒绝，但最多反馈八条，也不回显不可信引用内容。
    @Test
    void shouldBoundErrorsAndHideUnknownReferenceText() {
        var plan = mergePlan();
        plan.getMerges().getFirst().setSourceRefs(java.util.Collections.nCopies(20, "不可信引用内容"));
        var failure = assertThrows(MemoryConsolidationFormatException.class,
                () -> MemoryConsolidationValidator.validate(snapshot(), plan));
        assertEquals(8, failure.getMessage().lines().count());
        assertFalse(failure.getMessage().contains("不可信引用内容"));
    }

    // 数据库返回错误归属时，即使 ID 相同也不能修改。
    @Test
    void shouldRejectDifferentOwner() {
        var snapshot = snapshot();
        second.setUserId(USER_ID + 1);
        assertThrows(IllegalStateException.class, () -> writer.persist(snapshot, mergePlan()));
        verify(memories, never()).updateUserMemory(any());
    }

    // 会话记忆使用自己的表和归属，长期记忆不会被连带修改。
    @Test
    void shouldMergeSessionMemoriesWithoutTouchingUserMemories() {
        var a = session(1, "goal", "本周学习 Java，重点是集合。");
        var b = session(2, "currentGoal", "本周学习 Java，周五复习集合。");
        var state = state(SESSION, 20, 0);
        var snapshot = new MemoryConsolidationSnapshot(state, List.of(
                new MemoryConsolidationEntry("memory_1", a.getId(), a.getMemoryKey(), a.getMemoryTopic(), a.getMemorySummary(), a.getMemoryContent(), a.getUpdatedAt()),
                new MemoryConsolidationEntry("memory_2", b.getId(), b.getMemoryKey(), b.getMemoryTopic(), b.getMemorySummary(), b.getMemoryContent(), b.getUpdatedAt())));
        when(memories.lockSessionMemory(SESSION_ID, 1L)).thenReturn(a);
        when(memories.lockSessionMemory(SESSION_ID, 2L)).thenReturn(b);
        when(progress.lock(SESSION, SESSION_ID)).thenReturn(state);
        when(progress.markProcessed(SESSION, SESSION_ID, 20, 0)).thenReturn(1);
        var plan = mergePlan();
        plan.getMerges().getFirst().setMemoryContent("本周学习 Java，重点是集合，周五复习。");
        assertTrue(writer.persist(snapshot, plan));
        verify(memories).deleteSessionMemory(SESSION_ID, 2L);
        verify(memories, never()).lockUserMemory(any(), any());
        verify(memories, never()).deleteUserMemory(any(), any());
    }

    // 未到阈值时不读取正文，也不额外请求模型。
    @Test
    void shouldSkipBeforeThreshold() {
        when(progress.find(USER, USER_ID)).thenReturn(state(USER, 39, 20));
        coordinator(writer).consolidateIfNeeded(USER_ID, SESSION_ID);
        verifyNoInteractions(model);
        verify(memories, never()).recallUserMemory(any(), any());
    }

    // 一次跨过多个整数阈值仍触发一次，不依赖取余结果是否变小。
    @Test
    void shouldHandleLargeBurstAndReadPersistedProgressAfterRestart() {
        when(progress.find(USER, USER_ID)).thenReturn(state(USER, 65, 20));
        when(model.consolidate(any())).thenReturn(new MemoryConsolidationPlan());
        var persistence = mock(MemoryConsolidationPersistenceService.class);
        var coordinator = coordinator(persistence);
        coordinator.consolidateIfNeeded(USER_ID, SESSION_ID);
        verify(model).consolidate(argThat(snapshot -> snapshot.getChangeCount() == 65 && snapshot.getProcessedCount() == 20));
        // 新建协调服务模拟重启，仍从数据库读取已完成位置。
        when(progress.find(USER, USER_ID)).thenReturn(state(USER, 65, 65));
        coordinator(persistence).consolidateIfNeeded(USER_ID, SESSION_ID);
        verify(model, times(1)).consolidate(any());
    }

    // 模型失败不推进进度，下一次请求可以重新尝试。
    @Test
    void shouldKeepPendingProgressOnFailure() {
        when(model.consolidate(any())).thenThrow(new IllegalStateException("模拟模型失败"));
        var persistence = mock(MemoryConsolidationPersistenceService.class);
        assertDoesNotThrow(() -> coordinator(persistence).consolidateIfNeeded(USER_ID, SESSION_ID));
        verifyNoInteractions(persistence);
        verify(progress, never()).markProcessed(any(), any(), anyLong(), anyLong());
    }

    // 长期记忆整理失败后，当前会话仍应完成检查和保存。
    @Test
    void shouldContinueSessionWhenUserConsolidationFails() {
        var sessionMemory = session(1, "goal", "本周学习 Java 集合");
        when(progress.find(SESSION, SESSION_ID)).thenReturn(state(SESSION, 20, 0));
        when(memories.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of(sessionMemory));
        when(memories.recallSessionMemory(SESSION_ID, 1L)).thenReturn(sessionMemory);
        when(model.consolidate(any())).thenThrow(new IllegalStateException("注入长期记忆模型失败"));
        var persistence = mock(MemoryConsolidationPersistenceService.class);
        when(persistence.persist(any(), any())).thenReturn(true);
        coordinator(persistence).consolidateIfNeeded(USER_ID, SESSION_ID);
        verify(persistence).persist(argThat(snapshot -> snapshot.getScope() == SESSION), any());
        verify(persistence, never()).persist(argThat(snapshot -> snapshot.getScope() == USER), any());
    }

    // 输入顺序故意倒置，确认保存仍按 ID 加锁，然后才锁进度。
    @Test
    void shouldLockMemoryIdsInOrderBeforeProgress() {
        var original = snapshot();
        var reversed = new MemoryConsolidationSnapshot(state(USER, 20, 0), original.getEntries().reversed());
        assertTrue(writer.persist(reversed, mergePlan()));
        var order = inOrder(memories, progress);
        order.verify(memories).lockUserMemory(USER_ID, 1L);
        order.verify(memories).lockUserMemory(USER_ID, 2L);
        order.verify(progress).lock(USER, USER_ID);
    }

    // 单条记忆不需要合并，确认进度时无需模型调用。
    @Test
    void shouldAcknowledgeSingleMemoryWithoutModelCall() {
        when(memories.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(first));
        coordinator(writer).consolidateIfNeeded(USER_ID, SESSION_ID);
        verifyNoInteractions(model);
        verify(progress).markProcessed(USER, USER_ID, 20, 0);
    }

    // 关闭功能后不产生整理查询和模型费用。
    @Test
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);
        coordinator(writer).consolidateIfNeeded(USER_ID, SESSION_ID);
        verify(progress, never()).find(any(), any());
        verifyNoInteractions(model);
    }

    // 数量超限时保留原记录，不能只拿部分正文继续合并。
    @Test
    void shouldKeepPendingWhenScopeIsTooLarge() {
        properties.setMaxMemories(2);
        when(memories.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(first, second, user(3, "run", "每周跑步")));
        coordinator(writer).consolidateIfNeeded(USER_ID, SESSION_ID);
        verifyNoInteractions(model);
        verify(progress, never()).markProcessed(any(), any(), anyLong(), anyLong());
    }

    // 给整理模型完整正文和短引用，不泄露真实主键、归属或数据库时间。
    @Test
    void shouldSendCompleteContentAndNoDatabaseIdentity() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = messages -> {
            calls.incrementAndGet();
            String input = messages.get(1).getContent();
            assertTrue(input.contains("每周打三次"));
            assertTrue(input.contains("通常周六"));
            assertFalse(input.contains("ownerId"));
            assertFalse(input.contains("memoryId"));
            assertFalse(input.contains("updatedAt"));
            return new TextLlmResponse(new JsonMapper().writeValueAsString(mergePlan()));
        };
        assertEquals(1, new LlmMemoryConsolidator(client, new LlmRetryExecutor(), properties).consolidate(snapshot()).getMerges().size());
        assertEquals(1, calls.get());
    }

    // 第一次引用错误时让模型修复，第二次使用同一份引用快照。
    @Test
    void shouldRepairInvalidReferenceWithinLimit() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = messages -> {
            var plan = mergePlan();
            if (calls.incrementAndGet() == 1) {
                plan.getMerges().getFirst().setSourceRefs(List.of("memory_1", "memory_999"));
            } else {
                assertTrue(messages.get(1).getContent().contains("只能使用本次提供的 memoryRef"));
            }
            return new TextLlmResponse(new JsonMapper().writeValueAsString(plan));
        };
        new LlmMemoryConsolidator(client, new LlmRetryExecutor(), properties).consolidate(snapshot());
        assertEquals(2, calls.get());
    }

    // 修复带上原始快照、全部已发现的错误和前次分组，不再发送错误正文。
    @Test
    void shouldRepairAllGroupErrorsAgainstTheSameSnapshot() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = messages -> {
            var plan = mergePlan();
            if (calls.incrementAndGet() == 1) {
                plan.getMerges().getFirst().setSourceRefs(List.of("memory_1"));
                plan.getMerges().getFirst().setMemoryContent("不能重复发送的错误正文");
                plan.setConflicts(List.of(List.of("memory_1", "memory_2")));
            } else {
                String input = messages.get(1).getContent();
                assertTrue(input.contains("\"allowedMemoryRefs\":[\"memory_1\",\"memory_2\"]"));
                assertTrue(input.contains("每周打三次"));
                assertTrue(input.contains("通常周六"));
                assertTrue(input.contains("merges[0].sourceRefs：每组至少需要两个记忆引用"));
                assertTrue(input.contains("conflicts[0][0]：记忆引用不能重复或跨组合并"));
                assertTrue(input.contains("\"sourceRefs\":[\"memory_1\"]"));
                assertFalse(input.contains("不能重复发送的错误正文"));
            }
            return new TextLlmResponse(new JsonMapper().writeValueAsString(plan));
        };
        var result = new LlmMemoryConsolidator(client, new LlmRetryExecutor(), properties).consolidate(snapshot());
        assertEquals(1, result.getMerges().size());
        assertEquals(2, calls.get());
    }

    // 未知引用只以占位符回显，不能把模型生成的任意文本带进下一轮。
    @Test
    void shouldSanitizeUnknownReferencesInRepairStructure() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = messages -> {
            var plan = mergePlan();
            if (calls.incrementAndGet() == 1) {
                plan.getMerges().getFirst().setKeepRef("未知引用中的指令");
                plan.getMerges().getFirst().setSourceRefs(List.of("memory_1", "未知引用中的指令"));
            } else {
                assertFalse(messages.get(1).getContent().contains("未知引用中的指令"));
                assertTrue(messages.get(1).getContent().contains("[非本次引用]"));
            }
            return new TextLlmResponse(new JsonMapper().writeValueAsString(plan));
        };
        new LlmMemoryConsolidator(client, new LlmRetryExecutor(), properties).consolidate(snapshot());
        assertEquals(2, calls.get());
    }

    // 分组结构过大就省略它，保留具体错误，不能截出半段 JSON。
    @Test
    void shouldBoundRepairStructure() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = messages -> {
            var plan = mergePlan();
            if (calls.incrementAndGet() == 1) {
                plan.getMerges().getFirst().setSourceRefs(java.util.Collections.nCopies(400, "memory_1"));
            } else {
                assertTrue(messages.get(1).getContent().contains("上次分组结构过长，已省略"));
                assertTrue(messages.get(1).getContent().contains("首次出现于 merges[0].sourceRefs[0]"));
            }
            return new TextLlmResponse(new JsonMapper().writeValueAsString(plan));
        };
        new LlmMemoryConsolidator(client, new LlmRetryExecutor(), properties).consolidate(snapshot());
        assertEquals(2, calls.get());
    }

    // 原始请求刚好能放下时，新增修复反馈仍必须经过同一预算检查。
    @Test
    void shouldIncludeRepairFeedbackInInputBudget() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = messages -> {
            calls.incrementAndGet();
            properties.setMaxInputCharacters(messages.get(1).getContent().length() + MemoryConsolidationPrompt.CONTENT.length());
            return new TextLlmResponse("非法 JSON");
        };
        assertThrows(IllegalStateException.class,
                () -> new LlmMemoryConsolidator(client, new LlmRetryExecutor(), properties).consolidate(snapshot()));
        assertEquals(1, calls.get(), "第二次请求应在发给模型前被预算检查拦截");
    }

    // 持续返回非法格式时到达上限就停止，不无限修复。
    @Test
    void shouldStopAfterInvalidOutputLimit() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = messages -> { calls.incrementAndGet(); return new TextLlmResponse("不是 JSON"); };
        assertThrows(MemoryConsolidationFormatException.class,
                () -> new LlmMemoryConsolidator(client, new LlmRetryExecutor(), properties).consolidate(snapshot()));
        assertEquals(2, calls.get());
    }

    // 请求预算不足时在调用模型前停止，不能截断正文继续整理。
    @Test
    void shouldEnforceInputBudgetBeforeModelCall() {
        properties.setMaxInputCharacters(50);
        LlmClient client = mock(LlmClient.class);
        assertThrows(IllegalStateException.class,
                () -> new LlmMemoryConsolidator(client, new LlmRetryExecutor(), properties).consolidate(snapshot()));
        verifyNoInteractions(client);
    }

    // 内容不变的更新不写数据库，也不增加阈值计数。
    @Test
    void shouldNotCountUnchangedCandidate() {
        var context = context(List.of(first), List.of());
        var candidate = candidate(MemoryOperation.UPDATE, USER, List.of("memory_1"), "篮球", null, first.getMemoryContent());
        candidate.setMemoryTopic(first.getMemoryTopic());
        new MemoryCandidatePersistenceService(memories, progress).persist(context, "篮球", List.of(candidate));
        verify(memories, never()).updateUserMemory(any());
        verify(progress, never()).addChanges(any(), any(), anyLong());
    }

    // 一个候选修改两个真实目标时累计两次，初始记录数只用于首次建立进度。
    @Test
    void shouldCountActualChangedRecords() {
        var context = context(List.of(first, second), List.of());
        var candidate = candidate(MemoryOperation.UPDATE, USER, List.of("memory_1", "memory_2"), "改成足球", null, "最喜欢足球");
        new MemoryCandidatePersistenceService(memories, progress).persist(context, "改成足球", List.of(candidate));
        verify(progress).initialize(USER, USER_ID, 2);
        verify(progress).addChanges(USER, USER_ID, 2);
    }

    // 组装协调服务，测试中可以替换最终事务写入器。
    private MemoryConsolidationService coordinator(MemoryConsolidationPersistenceService persistence) {
        return new MemoryConsolidationService(properties, progress, memories, model, persistence,
                mock(MemoryConsolidationApprovalService.class));
    }

    // 创建数据库进度的测试副本，两类归属分别取对应 ID。
    static MemoryConsolidationState state(MemoryScope scope, long changes, long processed) {
        MemoryConsolidationState state = new MemoryConsolidationState();
        state.setScope(scope);
        state.setOwnerId(scope == USER ? USER_ID : SESSION_ID);
        state.setChangeCount(changes);
        state.setProcessedCount(processed);
        return state;
    }

    // 从当前实体复制字段，后续修改实体不会同步修改快照。
    private MemoryConsolidationSnapshot snapshot() {
        return new MemoryConsolidationSnapshot(state(USER, 20, 0), List.of(
                new MemoryConsolidationEntry("memory_1", first.getId(), first.getMemoryKey(), first.getMemoryTopic(), first.getMemorySummary(), first.getMemoryContent(), first.getUpdatedAt()),
                new MemoryConsolidationEntry("memory_2", second.getId(), second.getMemoryKey(), second.getMemoryTopic(), second.getMemorySummary(), second.getMemoryContent(), second.getUpdatedAt())));
    }

    // 合并两个相同偏好的互补细节，保持简单可核对的预期结果。
    static MemoryConsolidationPlan mergePlan() {
        MemoryMergeCandidate merge = new MemoryMergeCandidate();
        merge.setKeepRef("memory_1");
        merge.setSourceRefs(List.of("memory_1", "memory_2"));
        merge.setMemoryTopic("运动偏好");
        merge.setMemorySummary("最喜欢篮球，每周三次，周六和朋友打球。");
        merge.setMemoryContent("最喜欢篮球，每周打三次，通常周六和朋友一起打。");
        MemoryConsolidationPlan plan = new MemoryConsolidationPlan();
        plan.setMerges(List.of(merge));
        return plan;
    }
}
