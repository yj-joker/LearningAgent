package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.memory.impl.DatabaseStructuredMemoryService;
import com.yjjoker.learningagent.projectenum.MemoryStatusEnum;
import com.yjjoker.learningagent.repository.SessionMemoryRepository;
import com.yjjoker.learningagent.repository.UserMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseStructuredMemoryServiceTest {

    @Mock
    private UserMemoryRepository userMemoryRepository;

    @Mock
    private SessionMemoryRepository sessionMemoryRepository;

    @InjectMocks
    private DatabaseStructuredMemoryService memoryService;

    @Test
    void shouldSaveUserMemoryWithActiveStatusAndTimestamps() {
        // 保存测试验证服务层会补齐状态和时间，而不是把不完整实体直接交给 Repository。
        UserMemory memory = userMemory(20L);
        when(userMemoryRepository.save(memory)).thenReturn(1);

        UserMemory saved = memoryService.saveUserMemory(memory);

        assertEquals(MemoryStatusEnum.ACTIVE, saved.getStatus());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        verify(userMemoryRepository).save(memory);
    }

    @Test
    void shouldLoadOnlyIndexForUserMemory() {
        // 索引查询返回摘要对象，正文由 Repository 的另一条召回 SQL 单独读取。
        UserMemory indexItem = userMemory(20L);
        indexItem.setMemoryContent(null);
        when(userMemoryRepository.findActiveIndexByUserId(20L))
                .thenReturn(List.of(indexItem));

        List<UserMemory> result = memoryService.loadUserMemoryIndex(20L);

        assertEquals(1, result.size());
        assertNull(result.getFirst().getMemoryContent());
        verify(userMemoryRepository).findActiveIndexByUserId(20L);
    }

    @Test
    void shouldRecallUserMemoryContentByOwnerAndMemoryId() {
        // 召回时同时传入 userId 和 memoryId，防止用户读取其他用户的记忆。
        UserMemory memory = userMemory(20L);
        when(userMemoryRepository.findActiveById(20L, 1L)).thenReturn(memory);

        UserMemory result = memoryService.recallUserMemory(20L, 1L);

        assertEquals(memory, result);
        verify(userMemoryRepository).findActiveById(20L, 1L);
    }

    @Test
    void shouldUpdateExistingUserMemoryWithoutReplacingItsIdentity() {
        // 更新只修改记忆内容和摘要，主键与创建时间由数据库记录继续保留。
        UserMemory memory = userMemory(20L);
        memory.setId(1L);
        when(userMemoryRepository.update(memory)).thenReturn(1);

        UserMemory updated = memoryService.updateUserMemory(memory);

        assertEquals(memory, updated);
        assertNotNull(updated.getUpdatedAt());
        verify(userMemoryRepository).update(memory);
    }

    @Test
    void shouldRejectIncompleteSessionMemory() {
        // 第一阶段只允许保存完整的索引摘要和正文，避免后续召回得到空内容。
        SessionMemory memory = new SessionMemory();
        memory.setSessionId(100L);
        memory.setMemoryKey("current_goal");

        assertThrows(ClientDataErrorException.class,
                () -> memoryService.saveSessionMemory(memory));
    }

    @Test
    void shouldSoftDeleteSessionMemory() {
        // 假删除只依赖归属范围和记忆 ID，不会直接删除数据库记录。
        when(sessionMemoryRepository.softDelete(any(), any(), any())).thenReturn(1);

        memoryService.deleteSessionMemory(100L, 2L);

        verify(sessionMemoryRepository).softDelete(any(), any(), any());
    }

    private UserMemory userMemory(Long userId) {
        UserMemory memory = new UserMemory();
        memory.setId(1L);
        memory.setUserId(userId);
        memory.setMemoryKey("learning_language");
        memory.setMemoryTopic("learning_background");
        memory.setMemorySummary("用户正在学习 Java");
        memory.setMemoryContent("用户正在系统学习 Java 后端开发");
        return memory;
    }
}
