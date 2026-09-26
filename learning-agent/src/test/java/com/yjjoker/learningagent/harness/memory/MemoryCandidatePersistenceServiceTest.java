package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.memory.model.MemoryCandidate;
import com.yjjoker.learningagent.harness.memory.model.MemoryOperation;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import com.yjjoker.learningagent.harness.memory.service.MemoryCandidatePersistenceService;
import com.yjjoker.learningagent.harness.memory.service.StructuredMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MemoryCandidatePersistenceServiceTest {

    @Test
    void shouldRouteCandidatesByScopeAndOnlyCallSaveMethods() {
        StructuredMemoryService structuredMemoryService = mock(StructuredMemoryService.class);
        MemoryCandidatePersistenceService persistenceService =
                new MemoryCandidatePersistenceService(structuredMemoryService);

        MemoryCandidate userCandidate = candidate(
                MemoryScope.USER, MemoryOperation.CREATE,
                "favorite_sport", "preference", "喜欢篮球", "用户喜欢篮球"
        );
        MemoryCandidate sessionCandidate = candidate(
                MemoryScope.SESSION, MemoryOperation.CREATE,
                "current_goal", "task_state", "测试记忆", "当前正在测试记忆写入"
        );

        persistenceService.persist(20L, 10L, List.of(userCandidate, sessionCandidate));

        verify(structuredMemoryService).saveUserMemory(argThat(memory ->
                memory.getUserId().equals(20L)
                        && memory.getMemoryKey().equals("favorite_sport")
                        && memory.getMemoryContent().equals("用户喜欢篮球")
        ));
        verify(structuredMemoryService).saveSessionMemory(argThat(memory ->
                memory.getSessionId().equals(10L)
                        && memory.getMemoryKey().equals("current_goal")
                        && memory.getMemoryContent().equals("当前正在测试记忆写入")
        ));

        // 当前阶段明确只新增，不因为 memoryKey 相同而自动更新旧记录。
        verify(structuredMemoryService, never()).updateUserMemory(org.mockito.ArgumentMatchers.any(UserMemory.class));
        verify(structuredMemoryService, never()).updateSessionMemory(org.mockito.ArgumentMatchers.any(SessionMemory.class));
    }

    @Test
    void shouldSkipDatabaseWhenThereAreNoCandidates() {
        StructuredMemoryService structuredMemoryService = mock(StructuredMemoryService.class);
        MemoryCandidatePersistenceService persistenceService =
                new MemoryCandidatePersistenceService(structuredMemoryService);

        persistenceService.persist(20L, 10L, List.of());

        verify(structuredMemoryService, never()).saveUserMemory(org.mockito.ArgumentMatchers.any(UserMemory.class));
        verify(structuredMemoryService, never()).saveSessionMemory(org.mockito.ArgumentMatchers.any(SessionMemory.class));
    }

    @Test
    void shouldRejectCandidateWithoutScope() {
        StructuredMemoryService structuredMemoryService = mock(StructuredMemoryService.class);
        MemoryCandidatePersistenceService persistenceService =
                new MemoryCandidatePersistenceService(structuredMemoryService);
        MemoryCandidate candidate = candidate(null, "invalid", "topic", "summary", "content");

        assertThrows(RuntimeException.class,
                () -> persistenceService.persist(20L, 10L, List.of(candidate)));
    }

    @Test
    void shouldUpdateExistingMemoryByExactKey() {
        StructuredMemoryService structuredMemoryService = mock(StructuredMemoryService.class);
        UserMemory existing = new UserMemory();
        existing.setId(99L);
        existing.setUserId(20L);
        existing.setMemoryKey("favorite_sport");
        existing.setMemoryTopic("preference");
        existing.setMemorySummary("用户喜欢篮球");
        existing.setMemoryContent("用户喜欢篮球");
        org.mockito.Mockito.when(structuredMemoryService.findActiveUserMemoryByKey(20L, "favorite_sport"))
                .thenReturn(existing);
        MemoryCandidatePersistenceService persistenceService =
                new MemoryCandidatePersistenceService(structuredMemoryService);
        MemoryCandidate candidate = candidate(
                MemoryScope.USER, MemoryOperation.UPDATE,
                "favorite_sport", "preference", "用户现在喜欢足球", "用户明确表示现在更喜欢足球"
        );

        persistenceService.persist(20L, 10L, List.of(candidate));

        verify(structuredMemoryService).updateUserMemory(existing);
        verify(structuredMemoryService, never()).saveUserMemory(org.mockito.ArgumentMatchers.any(UserMemory.class));
    }

    @Test
    void shouldSoftDeleteExistingMemoryByExactKey() {
        StructuredMemoryService structuredMemoryService = mock(StructuredMemoryService.class);
        UserMemory existing = new UserMemory();
        existing.setId(99L);
        existing.setUserId(20L);
        existing.setMemoryKey("favorite_sport");
        org.mockito.Mockito.when(structuredMemoryService.findActiveUserMemoryByKey(20L, "favorite_sport"))
                .thenReturn(existing);
        MemoryCandidatePersistenceService persistenceService =
                new MemoryCandidatePersistenceService(structuredMemoryService);
        MemoryCandidate candidate = candidate(
                MemoryScope.USER, MemoryOperation.DELETE,
                "favorite_sport", "", "", ""
        );

        persistenceService.persist(20L, 10L, List.of(candidate));

        verify(structuredMemoryService).deleteUserMemory(20L, 99L);
        verify(structuredMemoryService, never()).saveUserMemory(org.mockito.ArgumentMatchers.any(UserMemory.class));
    }

    @Test
    void shouldSkipCreateWhenExactKeyAndContentAreSame() {
        StructuredMemoryService structuredMemoryService = mock(StructuredMemoryService.class);
        UserMemory existing = new UserMemory();
        existing.setUserId(20L);
        existing.setMemoryKey("favorite_sport");
        existing.setMemoryTopic("preference");
        existing.setMemorySummary("用户喜欢篮球");
        existing.setMemoryContent("用户喜欢篮球");
        org.mockito.Mockito.when(structuredMemoryService.findActiveUserMemoryByKey(20L, "favorite_sport"))
                .thenReturn(existing);
        MemoryCandidatePersistenceService persistenceService =
                new MemoryCandidatePersistenceService(structuredMemoryService);

        persistenceService.persist(20L, 10L, List.of(candidate(
                MemoryScope.USER, MemoryOperation.CREATE,
                "favorite_sport", "preference", "用户喜欢篮球", "用户喜欢篮球"
        )));

        verify(structuredMemoryService, never()).saveUserMemory(org.mockito.ArgumentMatchers.any(UserMemory.class));
        verify(structuredMemoryService, never()).updateUserMemory(org.mockito.ArgumentMatchers.any(UserMemory.class));
    }

    private MemoryCandidate candidate(MemoryScope scope,
                                      String key,
                                      String topic,
                                      String summary,
                                      String content) {
        return candidate(scope, MemoryOperation.CREATE, key, topic, summary, content);
    }

    private MemoryCandidate candidate(MemoryScope scope,
                                      MemoryOperation operation,
                                      String key,
                                      String topic,
                                      String summary,
                                      String content) {
        MemoryCandidate candidate = new MemoryCandidate();
        candidate.setScope(scope);
        candidate.setOperation(operation);
        candidate.setMemoryKey(key);
        candidate.setMemoryTopic(topic);
        candidate.setMemorySummary(summary);
        candidate.setMemoryContent(content);
        return candidate;
    }
}
