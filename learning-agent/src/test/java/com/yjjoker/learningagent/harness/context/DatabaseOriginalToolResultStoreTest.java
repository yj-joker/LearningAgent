package com.yjjoker.learningagent.harness.context;

import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.projectenum.LearningSessionMessageRoleEnum;
import com.yjjoker.learningagent.repository.LearningSessionMessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("数据库原始工具结果存储测试")
class DatabaseOriginalToolResultStoreTest {

    @Mock
    private LearningSessionMessageRepository messageRepository;

    @Test
    @DisplayName("内存没有结果时按会话和调用 ID 从数据库读取")
    void shouldLoadResultFromDatabaseWhenMemoryMisses() {
        LearningSessionMessage message = new LearningSessionMessage();
        message.setRole(LearningSessionMessageRoleEnum.TOOL);
        message.setToolCallId("call_old");
        message.setContent("原始数据库结果");
        when(messageRepository.findToolResult(10L, "call_old")).thenReturn(message);

        DatabaseOriginalToolResultStore store = new DatabaseOriginalToolResultStore(
                new InMemoryOriginalToolResultStore(), messageRepository
        );
        store.beginSession(10L);

        assertEquals("原始数据库结果", store.read("call_old", 0, 100));
        assertEquals(7, store.length("call_old"));
        verify(messageRepository).findToolResult(eq(10L), eq("call_old"));
    }

    @Test
    @DisplayName("没有会话上下文时不执行跨会话数据库读取")
    void shouldReturnMissingWhenSessionIsNotSet() {
        DatabaseOriginalToolResultStore store = new DatabaseOriginalToolResultStore(
                new InMemoryOriginalToolResultStore(), messageRepository
        );

        assertEquals(-1, store.length("call_missing"));
        assertEquals("", store.read("call_missing", 0, 100));
    }
}
