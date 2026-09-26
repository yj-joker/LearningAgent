package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.yjjoker.learningagent.harness.memory.MemoryTestData.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryOperation.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryScope.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MemoryCandidatePersistenceServiceTest {
    // 新事实仍按 scope 保存到不同的表，不触发更新或删除。
    @Test
    void shouldCreateBothScopes() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        var service = new MemoryCandidatePersistenceService(store);
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
        new MemoryCandidatePersistenceService(store).persist(snapshot, "现在最喜欢足球", List.of(
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
        new MemoryCandidatePersistenceService(store).persist(snapshot, "忘记最喜欢的运动", List.of(
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
        var service = new MemoryCandidatePersistenceService(store);
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
        assertThrows(RuntimeException.class, () -> new MemoryCandidatePersistenceService(store).persist(snapshot,
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
        assertThrows(RuntimeException.class, () -> new MemoryCandidatePersistenceService(store).persist(snapshot,
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
        assertThrows(RuntimeException.class, () -> new MemoryCandidatePersistenceService(store).persist(snapshot,
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
        assertThrows(MemoryExtractionFormatException.class, () -> new MemoryCandidatePersistenceService(store).persist(
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
                () -> new MemoryCandidatePersistenceService(store).persist(snapshot, "忘记", List.of(change, change)));
        verifyNoInteractions(store);
    }

    // 同一 scope 内同 key 的重复新增应在访问数据库前拒绝。
    @Test
    void shouldRejectDuplicateCreatesInOneBatch() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        var create = candidate(CREATE, USER, List.of(), "篮球", "sport", "篮球");
        assertThrows(MemoryExtractionFormatException.class, () -> new MemoryCandidatePersistenceService(store).persist(
                emptyContext(), "篮球", List.of(create, create)));
        verifyNoInteractions(store);
    }

    // 空候选不访问数据库，普通聊天保持轻量。
    @Test
    void shouldSkipEmptyCandidates() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        new MemoryCandidatePersistenceService(store).persist(emptyContext(), "你好", List.of());
        verifyNoInteractions(store);
    }

    // 并发请求已经新增相同内容时跳过，不把 CREATE 变成 UPDATE。
    @Test
    void shouldSkipIdenticalConcurrentCreate() {
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        UserMemory memory = user(1, "sport", "篮球");
        memory.setMemoryTopic("用户事实");
        when(store.findActiveUserMemoryByKey(USER_ID, "sport")).thenReturn(memory);
        new MemoryCandidatePersistenceService(store).persist(emptyContext(), "篮球", List.of(
                candidate(CREATE, USER, List.of(), "篮球", "sport", "篮球")));
        verify(store, never()).saveUserMemory(any());
        verify(store, never()).updateUserMemory(any());
    }
}
