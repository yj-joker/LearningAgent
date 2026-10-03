package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.entity.LearningSessionSummary;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.memory.impl.DatabaseConversationMemoryService;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.projectenum.LearningSessionMessageRoleEnum;
import com.yjjoker.learningagent.repository.LearningSessionMessageRepository;
import com.yjjoker.learningagent.repository.LearningSessionSummaryRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("数据库会话记忆服务测试")
class DatabaseConversationMemoryServiceTest {

    @Mock
    private LearningSessionMessageRepository messageRepository;

    @Mock
    private LearningSessionSummaryRepository summaryRepository;

    @Test
    @DisplayName("工具调用消息会转换成 JSON 后保存")
    void shouldSerializeToolCallsWhenSaving() {
        when(messageRepository.save(any())).thenReturn(1);
        DatabaseConversationMemoryService service = service();
        LlmMessage message = LlmMessage.assistantToolCalls(List.of(
                new ToolCall("call_1", "search_course", "{\"keyword\":\"事务\"}")
        ));

        service.appendMessage(10L, AgentMode.CHAT, message);

        ArgumentCaptor<LearningSessionMessage> captor =
                ArgumentCaptor.forClass(LearningSessionMessage.class);
        verify(messageRepository).save(captor.capture());
        LearningSessionMessage storedMessage = captor.getValue();
        assertEquals(10L, storedMessage.getSessionId());
        assertEquals(AgentMode.CHAT.name(), storedMessage.getAgentMode());
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
                "{\"success\":true,\"content\":\"完整资料\"}",
                null,
                "call_1"
        );
        toolResult.setContextContent("{\"success\":true,\"content\":\"压缩资料\"}");
        LearningSessionMessage assistantAnswer = storedMessage(
                LearningSessionMessageRoleEnum.ASSISTANT,
                "事务是一组不可分割的操作。",
                null,
                null
        );
        when(messageRepository.findReplayableBySessionId(10L, AgentMode.CHAT.name())).thenReturn(List.of(
                userMessage,
                assistantToolCall,
                toolResult,
                assistantAnswer
        ));

        List<LlmMessage> history = service().loadHistory(10L, AgentMode.CHAT);

        assertEquals(List.of("user", "assistant", "tool", "assistant"),
                history.stream().map(LlmMessage::getRole).toList());
        assertEquals("call_1", history.get(1).getToolCalls().getFirst().id());
        assertEquals("call_1", history.get(2).getToolCallId());
        assertEquals("{\"success\":true,\"content\":\"压缩资料\"}", history.get(2).getContent());
        assertEquals("{\"success\":true,\"content\":\"完整资料\"}",
                history.get(2).getOriginalContent());
        assertEquals("事务是一组不可分割的操作。", history.get(3).getContent());
        verify(messageRepository).findReplayableBySessionId(10L, AgentMode.CHAT.name());
    }

    @Test
    @DisplayName("存在摘要时只加载摘要和覆盖点之后的新消息")
    void shouldLoadLatestSummaryAndRecentMessages() {
        LearningSessionSummary summary = new LearningSessionSummary();
        summary.setSummaryContent("用户正在学习事务");
        summary.setCoveredUntilMessageId(20L);
        when(summaryRepository.findLatestBySessionId(10L, AgentMode.CHAT.name())).thenReturn(summary);

        LearningSessionMessage recentMessage = storedMessage(
                LearningSessionMessageRoleEnum.USER,
                "继续讲锁",
                null,
                null
        );
        when(messageRepository.findReplayableAfterMessageId(10L, AgentMode.CHAT.name(), 20L))
                .thenReturn(List.of(recentMessage));

        List<LlmMessage> history = service().loadHistory(10L, AgentMode.CHAT);

        assertEquals(2, history.size());
        assertEquals("用户正在学习事务", history.getFirst().getContent());
        assertTrue(history.getFirst().isSummary());
        assertEquals("继续讲锁", history.get(1).getContent());
        verify(messageRepository).findReplayableAfterMessageId(10L, AgentMode.CHAT.name(), 20L);
        verify(messageRepository, never()).findReplayableBySessionId(10L, AgentMode.CHAT.name());
    }

    // 同一会话的两个模式分别取自己的摘要和摘要后的新消息。
    @Test
    @DisplayName("问答与专注历史及摘要互不混用")
    void shouldLoadHistoryAndSummaryOnlyForRequestedMode() {
        LearningSessionSummary chatSummary = new LearningSessionSummary();
        chatSummary.setSummaryContent("问答摘要");
        chatSummary.setCoveredUntilMessageId(10L);
        LearningSessionSummary focusSummary = new LearningSessionSummary();
        focusSummary.setSummaryContent("专注摘要");
        focusSummary.setCoveredUntilMessageId(20L);
        when(summaryRepository.findLatestBySessionId(10L, AgentMode.CHAT.name())).thenReturn(chatSummary);
        when(summaryRepository.findLatestBySessionId(10L, AgentMode.FOCUS.name())).thenReturn(focusSummary);

        LearningSessionMessage chatMessage = storedMessage(
                LearningSessionMessageRoleEnum.USER, "问答新消息", null, null);
        LearningSessionMessage focusMessage = storedMessage(
                LearningSessionMessageRoleEnum.USER, "专注新消息", null, null);
        when(messageRepository.findReplayableAfterMessageId(10L, AgentMode.CHAT.name(), 10L))
                .thenReturn(List.of(chatMessage));
        when(messageRepository.findReplayableAfterMessageId(10L, AgentMode.FOCUS.name(), 20L))
                .thenReturn(List.of(focusMessage));

        DatabaseConversationMemoryService service = service();
        List<LlmMessage> chatHistory = service.loadHistory(10L, AgentMode.CHAT);
        List<LlmMessage> focusHistory = service.loadHistory(10L, AgentMode.FOCUS);

        assertEquals(List.of("问答摘要", "问答新消息"),
                chatHistory.stream().map(LlmMessage::getContent).toList());
        assertEquals(List.of("专注摘要", "专注新消息"),
                focusHistory.stream().map(LlmMessage::getContent).toList());
        verify(summaryRepository).findLatestBySessionId(10L, AgentMode.CHAT.name());
        verify(summaryRepository).findLatestBySessionId(10L, AgentMode.FOCUS.name());
        verify(messageRepository).findReplayableAfterMessageId(10L, AgentMode.CHAT.name(), 10L);
        verify(messageRepository).findReplayableAfterMessageId(10L, AgentMode.FOCUS.name(), 20L);
    }

    @Test
    @DisplayName("普通 assistant 消息不会因为正文前缀而被标记为摘要")
    void shouldDistinguishSummaryByMessageType() {
        assertFalse(LlmMessage.assistant("历史上下文摘要：这只是普通回答").isSummary());
        assertTrue(LlmMessage.summary("这是真正的摘要").isSummary());
    }

    @Test
    @DisplayName("工具原文和上下文副本会分别保存")
    void shouldPersistOriginalAndContextToolContentSeparately() {
        when(messageRepository.save(any())).thenReturn(1);
        DatabaseConversationMemoryService service = service();
        LlmMessage compactedToolMessage = LlmMessage
                .toolResult("call_large", "完整工具结果")
                .withContextContent("压缩工具结果");

        service.appendMessage(10L, AgentMode.FOCUS, compactedToolMessage);

        ArgumentCaptor<LearningSessionMessage> captor =
                ArgumentCaptor.forClass(LearningSessionMessage.class);
        verify(messageRepository).save(captor.capture());
        assertEquals("完整工具结果", captor.getValue().getContent());
        assertEquals("压缩工具结果", captor.getValue().getContextContent());
    }

    @Test
    @DisplayName("历史工具结果的新压缩副本会回写数据库")
    void shouldUpdatePersistedToolContextCopy() {
        DatabaseConversationMemoryService service = service();
        LlmMessage compactedToolMessage = LlmMessage
                .toolResult("call_old", "完整历史结果")
                .withContextContent("更小的上下文副本");

        service.updateToolContextCopies(10L, AgentMode.FOCUS, List.of(compactedToolMessage));

        verify(messageRepository).updateToolContextContent(
                10L,
                AgentMode.FOCUS.name(),
                "call_old",
                "更小的上下文副本"
        );
    }

    @Test
    @DisplayName("不可重放消息仍会带标记保存到数据库")
    void shouldPersistNonReplayableMessageForAudit() {
        when(messageRepository.save(any())).thenReturn(1);
        DatabaseConversationMemoryService service = service();
        LlmMessage recoveryResult = LlmMessage.toolResult(
                "call_restore",
                "恢复片段",
                false
        );

        service.appendMessage(10L, AgentMode.CHAT, recoveryResult);

        ArgumentCaptor<LearningSessionMessage> captor =
                ArgumentCaptor.forClass(LearningSessionMessage.class);
        verify(messageRepository).save(captor.capture());
        assertFalse(captor.getValue().isContextReplayable());
        assertEquals("恢复片段", captor.getValue().getContent());
    }

    @Test
    @DisplayName("摘要会归档旧历史并保存为新的可重放消息")
    void shouldArchiveHistoryAndPersistSummary() {
        when(messageRepository.findMaxMessageId(10L, AgentMode.FOCUS.name())).thenReturn(42L);
        when(summaryRepository.save(any())).thenReturn(1);

        service().replaceReplayableHistoryWithSummary(
                10L,
                AgentMode.FOCUS,
                LlmMessage.summary("用户正在学习事务")
        );

        ArgumentCaptor<LearningSessionSummary> captor =
                ArgumentCaptor.forClass(LearningSessionSummary.class);
        verify(summaryRepository).save(captor.capture());
        assertEquals(10L, captor.getValue().getSessionId());
        assertEquals(AgentMode.FOCUS.name(), captor.getValue().getAgentMode());
        assertEquals(42L, captor.getValue().getCoveredUntilMessageId());
    }

    @Test
    @DisplayName("System Prompt 不允许保存到会话历史")
    void shouldRejectSystemMessage() {
        DatabaseConversationMemoryService service = service();

        LearningAgentServiceException exception = assertThrows(
                LearningAgentServiceException.class,
                () -> service.appendMessage(10L, AgentMode.CHAT, LlmMessage.system("系统规则"))
        );

        assertEquals("System Prompt 不能保存到会话历史", exception.getMessage());
        verify(messageRepository, never()).save(any());
    }

    private DatabaseConversationMemoryService service() {
        return new DatabaseConversationMemoryService(messageRepository, summaryRepository);
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
