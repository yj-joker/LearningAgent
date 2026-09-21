package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.projectenum.LearningSessionMessageRoleEnum;
import com.yjjoker.learningagent.repository.LearningSessionMessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("数据库会话记忆服务测试")
class DatabaseConversationMemoryServiceTest {

    @Mock
    private LearningSessionMessageRepository messageRepository;

    @Test
    @DisplayName("工具调用消息会转换成 JSON 后保存")
    void shouldSerializeToolCallsWhenSaving() {
        when(messageRepository.save(any())).thenReturn(1);
        DatabaseConversationMemoryService service = service();
        LlmMessage message = LlmMessage.assistantToolCalls(List.of(
                new ToolCall("call_1", "search_course", "{\"keyword\":\"事务\"}")
        ));

        service.appendMessage(10L, message);

        ArgumentCaptor<LearningSessionMessage> captor =
                ArgumentCaptor.forClass(LearningSessionMessage.class);
        verify(messageRepository).save(captor.capture());
        LearningSessionMessage storedMessage = captor.getValue();
        assertEquals(10L, storedMessage.getSessionId());
        assertEquals(LearningSessionMessageRoleEnum.ASSISTANT, storedMessage.getRole());
        assertNotNull(storedMessage.getCreatedAt());
        assertFalse(storedMessage.getToolCallsJson().isBlank());
    }

    @Test
    @DisplayName("数据库消息会还原成模型消息")
    void shouldRestoreMessagesInRepositoryOrder() {
        LearningSessionMessage userMessage = storedMessage(
                LearningSessionMessageRoleEnum.USER,
                "什么是事务？",
                null,
                null
        );
        LearningSessionMessage assistantToolCall = storedMessage(
                LearningSessionMessageRoleEnum.ASSISTANT,
                null,
                "[{\"id\":\"call_1\",\"name\":\"search_course\",\"arguments\":\"{}\"}]",
                null
        );
        LearningSessionMessage toolResult = storedMessage(
                LearningSessionMessageRoleEnum.TOOL,
                "{\"success\":true}",
                null,
                "call_1"
        );
        LearningSessionMessage assistantAnswer = storedMessage(
                LearningSessionMessageRoleEnum.ASSISTANT,
                "事务是一组不可分割的操作。",
                null,
                null
        );
        when(messageRepository.findBySessionId(10L)).thenReturn(List.of(
                userMessage,
                assistantToolCall,
                toolResult,
                assistantAnswer
        ));

        List<LlmMessage> history = service().loadHistory(10L);

        assertEquals(List.of("user", "assistant", "tool", "assistant"),
                history.stream().map(LlmMessage::getRole).toList());
        assertEquals("call_1", history.get(1).getToolCalls().getFirst().id());
        assertEquals("call_1", history.get(2).getToolCallId());
        assertEquals("事务是一组不可分割的操作。", history.get(3).getContent());
    }

    @Test
    @DisplayName("System Prompt 不允许保存到会话历史")
    void shouldRejectSystemMessage() {
        DatabaseConversationMemoryService service = service();

        LearningAgentServiceException exception = assertThrows(
                LearningAgentServiceException.class,
                () -> service.appendMessage(10L, LlmMessage.system("系统规则"))
        );

        assertEquals("System Prompt 不能保存到会话历史", exception.getMessage());
        verify(messageRepository, never()).save(any());
    }

    private DatabaseConversationMemoryService service() {
        return new DatabaseConversationMemoryService(messageRepository);
    }

    private LearningSessionMessage storedMessage(LearningSessionMessageRoleEnum role,
                                                 String content,
                                                 String toolCallsJson,
                                                 String toolCallId) {
        LearningSessionMessage message = new LearningSessionMessage();
        message.setRole(role);
        message.setContent(content);
        message.setToolCallsJson(toolCallsJson);
        message.setToolCallId(toolCallId);
        return message;
    }
}
