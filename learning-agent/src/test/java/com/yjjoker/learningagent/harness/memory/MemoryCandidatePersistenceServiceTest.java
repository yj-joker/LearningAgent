package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.repository.MemoryConsolidationRepository;

import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.*;
import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.yjjoker.learningagent.harness.memory.MemoryTestData.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryOperation.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryScope.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MemoryCandidatePersistenceServiceTest {
    // 直接调用保存入口也不能重复改删已处理的目标，不能只依赖提取阶段检查。
    @Test
    void shouldRejectRepeatedTargetBeforeAnyDatabaseAccess() {
        var first = user(1, "sport", "喜欢足球");
        var receipt = new MemoryWriteReceipt(UPDATE, USER, USER_ID, List.of(1L), List.of("sport"));
        var context = contextWithWrite(receipt, List.of(first), List.of());
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        MemoryConsolidationRepository counts = mock(MemoryConsolidationRepository.class);
        var service = new MemoryCandidatePersistenceService(store, counts);
        // UPDATE 和 DELETE 都不能第二次处理同一目标。
        for (var operation : List.of(UPDATE, DELETE)) {
            var repeated = candidate(operation, USER, List.of("memory_1"), "喜欢足球", null, "喜欢足球");
            assertThrows(MemoryExtractionFormatException.class,
                    () -> service.persist(context, "喜欢足球", List.of(repeated)));
        }
        verifyNoInteractions(store, counts);
    }

    // 删除后原目标已不在最新索引；换成任何新 key 也不能在同一范围重新新增。
    @Test
    void shouldRejectAliasRecreationAfterCommittedDelete() {
        var receipt = new MemoryWriteReceipt(DELETE, USER, USER_ID, List.of(1L), List.of("sport"));
        var context = contextWithWrite(receipt, List.of(), List.of());
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        MemoryConsolidationRepository counts = mock(MemoryConsolidationRepository.class);
        var candidate = candidate(CREATE, USER, List.of(), "喜欢篮球", "completelyDifferentAlias", "喜欢篮球");
        assertThrows(MemoryExtractionFormatException.class,
                () -> new MemoryCandidatePersistenceService(store, counts).persist(context, "忘记喜欢篮球这件事", List.of(candidate)));
        verifyNoInteractions(store, counts);
    }

    // 新增已经完成时，不能再以另一个 key 新增同一范围的事实。
    @Test
    void shouldRejectAnotherCreateAfterCommittedCreate() {
        var receipt = new MemoryWriteReceipt(CREATE, USER, USER_ID, List.of(1L), List.of("sport"));
        var context = contextWithWrite(receipt, List.of(user(1, "sport", "喜欢篮球")), List.of());
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        var service = new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class));
        assertThrows(MemoryExtractionFormatException.class, () -> service.persist(context, "喜欢篮球", List.of(
                candidate(CREATE, USER, List.of(), "喜欢篮球", "sportAlias", "喜欢篮球"))));
        verifyNoInteractions(store);
    }

    // 失败、拒绝和只有成功文字的结果都不能成为后置提取绕过限制的理由。
    @Test
    void shouldRejectAutomaticWritesAfterUnconfirmedAttempts() {
        for (String outcome : List.of("FAILED", "REJECTED", "ERROR", "NO_RECEIPT")) {
            AgentRunContext run = new AgentRunContext();
            ToolCall call = new ToolCall("write_call", "write_memory", "{}");
            run.requestToolExecution(call);
            run.classifyToolExecution(call, true);
            if (outcome.equals("REJECTED")) {
                run.rejectToolExecution(call, ToolExecutionResult.failure("DENIED", "禁止修改", false));
            } else if (outcome.equals("ERROR")) {
                run.startToolExecution(call);
                run.markFailed(new IllegalStateException("模拟异常"));
            } else {
                run.startToolExecution(call);
                run.completeToolExecution(call, outcome.equals("NO_RECEIPT")
                        ? ToolExecutionResult.success("已删除")
                        : ToolExecutionResult.failure("NOT_FOUND", "没有找到", true));
            }
            var context = new MemoryExtractionContext(USER_ID, SESSION_ID,
                    new MemoryIndexSnapshot(List.of(user(1, "sport", "喜欢足球")), List.of()), run.getToolExecutions());
            StructuredMemoryService store = mock(StructuredMemoryService.class);
            var service = new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class));
            assertThrows(MemoryExtractionFormatException.class, () -> service.persist(context, "删除运动", List.of(
                    candidate(DELETE, USER, List.of("memory_1"), "删除运动", null, null))), outcome);
            verifyNoInteractions(store);
        }
    }

    // 有凭据的正常写入只保护对应目标；其他已有目标仍可按原有权限和快照规则修改。
    @Test
    void shouldAllowUnrelatedExistingTargetsAndOtherScope() {
        var sport = user(1, "sport", "喜欢足球");
        var running = user(2, "running", "每周跑步三次");
        var context = contextWithWrite(new MemoryWriteReceipt(UPDATE, USER, USER_ID, List.of(1L), List.of("sport")),
                List.of(sport, running), List.of());
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        when(store.lockUserMemory(USER_ID, 2L)).thenReturn(running);
        new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(context,
                "每周跑步五次，今天学 Java", List.of(
                        candidate(UPDATE, USER, List.of("memory_2"), "每周跑步五次", null, "每周跑步五次"),
                        candidate(CREATE, SESSION, List.of(), "今天学 Java", "goal", "学习 Java")));
        verify(store).updateUserMemory(running);
        verify(store).saveSessionMemory(any());
        verify(store, never()).lockUserMemory(USER_ID, 1L);
    }

    // 同一个数字 ID 在两张记忆表中代表不同目标，保护长期记忆不能误伤会话记忆。
    @Test
    void shouldKeepTargetProtectionScopedToItsOwnerAndTable() {
        var sessionGoal = session(1, "goal", "学习 Java");
        var context = contextWithWrite(new MemoryWriteReceipt(UPDATE, USER, USER_ID, List.of(1L), List.of("sport")),
                List.of(user(1, "sport", "喜欢足球")), List.of(sessionGoal));
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        when(store.lockSessionMemory(SESSION_ID, 1L)).thenReturn(sessionGoal);
        new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(context,
                "改学网络", List.of(candidate(UPDATE, SESSION, List.of("memory_2"), "改学网络", null, "学习网络")));
        verify(store).updateSessionMemory(sessionGoal);
        verify(store, never()).lockUserMemory(anyLong(), anyLong());
    }

    // 不正确的用户或会话归属在建立输入时就拒绝，不能先把凭据正文发给模型。
    @Test
    void shouldRejectForeignReceiptBeforeExtraction() {
        for (var scope : MemoryScope.values()) {
            var foreignReceipt = new MemoryWriteReceipt(UPDATE, scope, 999L, List.of(1L), List.of("privateFact"));
            assertThrows(IllegalStateException.class, () -> contextWithWrite(foreignReceipt, List.of(), List.of()));
        }
    }

    // 查询工具如果返回了写入凭据，说明工具声明错误，不能悄悄过滤掉这次写入。
    @Test
    void shouldRejectReceiptFromMisclassifiedTool() {
        AgentRunContext run = new AgentRunContext();
        ToolCall call = new ToolCall("call_read", "read_memory", "{}");
        run.requestToolExecution(call);
        run.classifyToolExecution(call, false);
        run.startToolExecution(call);
        run.completeToolExecution(call, ToolExecutionResult.memoryWriteSuccess("已修改",
                new MemoryWriteReceipt(UPDATE, USER, USER_ID, List.of(1L), List.of("sport"))));
        assertThrows(IllegalStateException.class, () -> new MemoryExtractionContext(USER_ID, SESSION_ID,
                new MemoryIndexSnapshot(List.of(), List.of()), run.getToolExecutions()));
    }

    // 构造已完成的合成工具调用；测试后置校验，不冒充真实数据库提交。
    private MemoryExtractionContext contextWithWrite(MemoryWriteReceipt receipt,
                                                     List<UserMemory> users, List<SessionMemory> sessions) {
        AgentRunContext run = new AgentRunContext();
        ToolCall call = new ToolCall("write_call", "write_memory", "{}");
        run.requestToolExecution(call);
        run.classifyToolExecution(call, true);
        run.startToolExecution(call);
        run.completeToolExecution(call, ToolExecutionResult.memoryWriteSuccess("处理完成", receipt));
        return new MemoryExtractionContext(USER_ID, SESSION_ID, new MemoryIndexSnapshot(users, sessions), run.getToolExecutions());
    }

    // 新事实仍按 scope 保存到不同的表，不触发更新或删除。
    @Test
    void shouldCreateBothScopes() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        var service = new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class));
        service.persist(emptyContext(), "喜欢篮球，今天学 Java", List.of(
                candidate(CREATE, USER, List.of(), "喜欢篮球", "sport", "喜欢篮球"),
                candidate(CREATE, SESSION, List.of(), "今天学 Java", "goal", "学习 Java")));
        verify(store).saveUserMemory(argThat(m -> USER_ID.equals(m.getUserId()) && "sport".equals(m.getMemoryKey())));
        verify(store).saveSessionMemory(argThat(m -> SESSION_ID.equals(m.getSessionId()) && "goal".equals(m.getMemoryKey())));
        verify(store, never()).updateUserMemory(any());
    }

    // 两个同义 key 一起变成足球，未选中的跑步频率保持原样。
    @Test
    void shouldUpdateEveryAliasWithoutChangingKeysOrUnrelatedFacts() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        UserMemory first = user(1, "favoriteSport", "最喜欢羽毛球");
        UserMemory second = user(2, "userFavoriteSport", "最喜欢羽毛球");
        UserMemory running = user(3, "runningFrequency", "每周跑步三次");
        var snapshot = context(List.of(first, second, running), List.of());
        when(store.lockUserMemory(USER_ID, 1L)).thenReturn(first);
        when(store.lockUserMemory(USER_ID, 2L)).thenReturn(second);
        new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(snapshot, "现在最喜欢足球", List.of(
                candidate(UPDATE, USER, List.of("memory_2", "memory_1"), "最喜欢足球", null, "最喜欢足球")));
        assertEquals("最喜欢足球", first.getMemoryContent());
        assertEquals("最喜欢足球", second.getMemoryContent());
        assertEquals("favoriteSport", first.getMemoryKey());
        assertEquals("userFavoriteSport", second.getMemoryKey());
        assertEquals("每周跑步三次", running.getMemoryContent());
        verify(store, never()).lockUserMemory(USER_ID, 3L);
        // 无论模型返回什么顺序，都先按 ID 加锁，再执行所有更新。
        var order = inOrder(store);
        order.verify(store).lockUserMemory(USER_ID, 1L);
        order.verify(store).lockUserMemory(USER_ID, 2L);
        order.verify(store).updateUserMemory(second);
        order.verify(store).updateUserMemory(first);
    }

    // 删除所有选中的同义记录，其他运动信息不受影响。
    @Test
    void shouldDeleteEveryAliasAndLeaveUnrelatedMemory() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        UserMemory first = user(1, "favoriteSport", "最喜欢羽毛球");
        UserMemory second = user(2, "userFavoriteSport", "最喜欢羽毛球");
        var snapshot = context(List.of(first, second, user(3, "running", "每周跑步三次")), List.of());
        when(store.lockUserMemory(USER_ID, 1L)).thenReturn(first);
        when(store.lockUserMemory(USER_ID, 2L)).thenReturn(second);
        new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(snapshot, "忘记最喜欢的运动", List.of(
                candidate(DELETE, USER, List.of("memory_1", "memory_2"), "忘记最喜欢的运动", null, null)));
        verify(store).deleteUserMemory(USER_ID, 1L);
        verify(store).deleteUserMemory(USER_ID, 2L);
        verify(store, never()).deleteUserMemory(USER_ID, 3L);
        verify(store, never()).saveUserMemory(any());
    }

    // 会话记忆使用相同的多目标规则，但只调用会话表的方法。
    @Test
    void shouldUpdateAndDeleteSessionTargets() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        SessionMemory first = session(1, "goal", "学习 Java");
        SessionMemory second = session(2, "currentGoal", "学习 Java");
        when(store.lockSessionMemory(SESSION_ID, 1L)).thenReturn(first);
        when(store.lockSessionMemory(SESSION_ID, 2L)).thenReturn(second);
        var service = new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class));
        service.persist(context(List.of(), List.of(first, second)), "改学网络", List.of(
                candidate(UPDATE, SESSION, List.of("memory_1", "memory_2"), "改学网络", null, "学习网络")));
        assertEquals("学习网络", first.getMemoryContent());
        assertEquals("学习网络", second.getMemoryContent());
        service.persist(context(List.of(), List.of(first, second)), "删除当前目标", List.of(
                candidate(DELETE, SESSION, List.of("memory_1", "memory_2"), "删除当前目标", null, null)));
        verify(store).deleteSessionMemory(SESSION_ID, 1L);
        verify(store).deleteSessionMemory(SESSION_ID, 2L);
        verify(store, never()).lockUserMemory(anyLong(), anyLong());
    }

    // 第二个目标已失效时，不能先把第一个目标更新掉。
    @Test
    void shouldValidateAllTargetsBeforeAnyWrite() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        UserMemory first = user(1, "sport", "最喜欢羽毛球");
        var snapshot = context(List.of(first, user(2, "alias", "最喜欢羽毛球")), List.of());
        when(store.lockUserMemory(USER_ID, 1L)).thenReturn(first);
        assertThrows(RuntimeException.class, () -> new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(snapshot,
                "最喜欢足球", List.of(candidate(UPDATE, USER, List.of("memory_1", "memory_2"), "最喜欢足球", null, "足球"))));
        verify(store, never()).updateUserMemory(any());
        assertEquals("最喜欢羽毛球", first.getMemoryContent());
    }

    // 模型思考期间目标内容改变，旧快照不得覆盖新值。
    @Test
    void shouldRejectChangedSnapshot() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        UserMemory memory = user(1, "sport", "喜欢篮球");
        var snapshot = context(List.of(memory), List.of());
        memory.setMemorySummary("喜欢游泳");
        when(store.lockUserMemory(USER_ID, 1L)).thenReturn(memory);
        assertThrows(RuntimeException.class, () -> new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(snapshot,
                "删除运动", List.of(candidate(DELETE, USER, List.of("memory_1"), "删除运动", null, null))));
        verify(store, never()).deleteUserMemory(anyLong(), anyLong());
    }

    // 即使数据访问层意外返回其他用户的记录，也不允许执行删除。
    @Test
    void shouldRejectWrongOwnerFromDatabase() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        var snapshot = context(List.of(user(1, "sport", "篮球")), List.of());
        UserMemory wrongOwner = user(1, "sport", "篮球");
        wrongOwner.setUserId(999L);
        when(store.lockUserMemory(USER_ID, 1L)).thenReturn(wrongOwner);
        assertThrows(RuntimeException.class, () -> new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(snapshot,
                "忘记", List.of(candidate(DELETE, USER, List.of("memory_1"), "忘记", null, null))));
        verify(store, never()).deleteUserMemory(anyLong(), anyLong());
    }

    // 索引构建阶段就拒绝其他用户或会话的数据。
    @Test
    void shouldRejectIndexFromAnotherOwner() {
        UserMemory user = user(1, "sport", "篮球");
        user.setUserId(999L);
        assertThrows(IllegalArgumentException.class, () -> context(List.of(user), List.of()));
        SessionMemory session = session(1, "goal", "Java");
        session.setSessionId(999L);
        assertThrows(IllegalArgumentException.class, () -> context(List.of(), List.of(session)));
    }

    // 没有本轮用户证据的候选不能绕过提取层直接保存。
    @Test
    void shouldRejectUngroundedCandidateAtPersistenceBoundary() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        assertThrows(MemoryExtractionFormatException.class, () -> new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(
                emptyContext(), "你好", List.of(candidate(CREATE, USER, List.of(), "喜欢篮球", "sport", "篮球"))));
        verifyNoInteractions(store);
    }

    // 两个候选重复操作同一引用时整批拒绝，避免顺序影响最终结果。
    @Test
    void shouldRejectOverlappingCandidateTargets() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        var snapshot = context(List.of(user(1, "sport", "篮球")), List.of());
        var change = candidate(DELETE, USER, List.of("memory_1"), "忘记", null, null);
        assertThrows(MemoryExtractionFormatException.class,
                () -> new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(snapshot, "忘记", List.of(change, change)));
        verifyNoInteractions(store);
    }

    // 同一 scope 内同 key 的重复新增应在访问数据库前拒绝。
    @Test
    void shouldRejectDuplicateCreatesInOneBatch() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        var create = candidate(CREATE, USER, List.of(), "篮球", "sport", "篮球");
        assertThrows(MemoryExtractionFormatException.class, () -> new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(
                emptyContext(), "篮球", List.of(create, create)));
        verifyNoInteractions(store);
    }

    // 空候选不访问数据库，普通聊天保持轻量。
    @Test
    void shouldSkipEmptyCandidates() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(emptyContext(), "你好", List.of());
        verifyNoInteractions(store);
    }

    // 并发请求已经新增相同内容时跳过，不把 CREATE 变成 UPDATE。
    @Test
    void shouldSkipIdenticalConcurrentCreate() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        UserMemory memory = user(1, "sport", "篮球");
        memory.setMemoryTopic("用户事实");
        when(store.findActiveUserMemoryByKey(USER_ID, "sport")).thenReturn(memory);
        new MemoryCandidatePersistenceService(store, mock(MemoryConsolidationRepository.class)).persist(emptyContext(), "篮球", List.of(
                candidate(CREATE, USER, List.of(), "篮球", "sport", "篮球")));
        verify(store, never()).saveUserMemory(any());
        verify(store, never()).updateUserMemory(any());
    }
}
