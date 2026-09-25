package com.yjjoker.learningagent.harness;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.impl.InMemoryOriginalToolResultStoreImpl;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.memory.ConversationMemoryService;
import com.yjjoker.learningagent.harness.memory.MemoryReferenceRegistry;
import com.yjjoker.learningagent.harness.memory.StructuredMemoryService;
import com.yjjoker.learningagent.harness.tool.impl.RecallMemoryTool;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentHarnessStructuredMemoryIntegrationTest {

    // 测试使用固定用户和会话，验证索引查询的作用域。
    private static final Long USER_ID = 20L;
    private static final Long SESSION_ID = 10L;

    @AfterEach
    void clearUserContext() {
        // ThreadLocal 必须清理，避免影响其他测试用例。
        BaseContext.removeCurrentId();
    }

    @Test
    void shouldSendMemoryIndexButNotMemoryContentToLlm() {
        // Harness 的权限校验依赖当前登录用户。
        BaseContext.setCurrentId(USER_ID);

        // 长期记忆同时准备摘要和正文，验证只发送摘要。
        UserMemory userMemory = new UserMemory();
        userMemory.setId(1L);
        userMemory.setUserId(USER_ID);
        userMemory.setMemoryKey("learning_language");
        userMemory.setMemoryTopic("learning_background");
        userMemory.setMemorySummary("用户正在学习 Java");
        userMemory.setMemoryContent("这是不应该直接发送给模型的长期记忆正文");

        // 会话记忆同样同时准备摘要和正文，验证不会越过索引阶段。
        SessionMemory sessionMemory = new SessionMemory();
        sessionMemory.setId(2L);
        sessionMemory.setSessionId(SESSION_ID);
        sessionMemory.setMemoryKey("current_goal");
        sessionMemory.setMemoryTopic("task_state");
        sessionMemory.setMemorySummary("当前正在接入结构化记忆");
        sessionMemory.setMemoryContent("这是不应该直接发送给模型的会话记忆正文");

        // Mock 记忆服务，只返回本测试需要的两条索引。
        StructuredMemoryService structuredMemoryService = mock(StructuredMemoryService.class);
        when(structuredMemoryService.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(userMemory));
        when(structuredMemoryService.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of(sessionMemory));

        // 记录模型收到的消息，检查 system prompt 的实际内容。
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        when(conversationMemoryService.loadHistory(SESSION_ID)).thenReturn(List.of());

        // Mock 会话仓库，让 Harness 通过访问权限校验。
        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessionRepository.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));

        // 直接使用带 StructuredMemoryService 的生产构造器，验证真实接入路径。
        AgentHarnessService harness = AgentHarnessTestFactory.create(
                llmClient,
                new ToolRegistry(List.of()),
                List.of(),
                conversationMemoryService,
                sessionRepository,
                new ContextManager(40_000, 8_000),
                new InMemoryOriginalToolResultStoreImpl(),
                structuredMemoryService
        );

        harness.run(SESSION_ID, "继续当前学习任务");

        // 第一条消息是 system，记忆索引应该位于其中。
        String systemPrompt = llmClient.messages.getFirst().getFirst().getContent();
        assertTrue(systemPrompt.contains("memoryRef=memory_1"));
        assertTrue(systemPrompt.contains("memoryRef=memory_2"));
        assertFalse(systemPrompt.contains("memoryId=1"));
        assertFalse(systemPrompt.contains("memoryId=2"));
        assertTrue(systemPrompt.contains("learning_language"));
        assertTrue(systemPrompt.contains("当前正在接入结构化记忆"));
        assertFalse(systemPrompt.contains("这是不应该直接发送给模型的长期记忆正文"));
        assertFalse(systemPrompt.contains("这是不应该直接发送给模型的会话记忆正文"));
    }

    @Test
    void shouldUseMemoryRefToRecallContentInsideAgentLoop() {
        BaseContext.setCurrentId(USER_ID);

        // 索引中的摘要只说明主题，正文由召回工具返回。
        UserMemory memory = new UserMemory();
        memory.setId(998877L);
        memory.setUserId(USER_ID);
        memory.setMemoryKey("sports_preference");
        memory.setMemoryTopic("personal_preference");
        memory.setMemorySummary("用户的运动偏好");
        memory.setMemoryContent("用户喜欢篮球，并且每周打三次球");

        StructuredMemoryService memoryService = mock(StructuredMemoryService.class);
        when(memoryService.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(memory));
        when(memoryService.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of());
        when(memoryService.recallUserMemory(USER_ID, memory.getId())).thenReturn(memory);

        MemoryReferenceRegistry referenceRegistry = new MemoryReferenceRegistry();
        SequenceLlmClient llmClient = new SequenceLlmClient(
                new ToolCallLlmResponse(List.of(new ToolCall(
                        "call_memory", "recall_memory", "{\"memoryRef\":\"memory_1\"}"
                ))),
                new TextLlmResponse("你喜欢篮球，并且每周打三次球。")
        );

        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessionRepository.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));

        AgentHarnessService harness = AgentHarnessTestFactory.create(
                llmClient,
                new ToolRegistry(List.of(new RecallMemoryTool(referenceRegistry, memoryService))),
                List.of(),
                mock(ConversationMemoryService.class),
                sessionRepository,
                new ContextManager(40_000, 8_000),
                new InMemoryOriginalToolResultStoreImpl(),
                memoryService,
                referenceRegistry
        );

        String answer = harness.run(SESSION_ID, "我喜欢什么运动？");

        assertEquals("你喜欢篮球，并且每周打三次球。", answer);
        assertTrue(llmClient.messages.get(1).stream()
                .anyMatch(message -> "tool".equals(message.getRole())
                        && message.getContent().contains("用户喜欢篮球")));
    }

    // 假模型只返回文本，测试重点放在 Harness 发出的第一份上下文。
    private static class RecordingLlmClient implements LlmClient {

        private final java.util.ArrayList<List<LlmMessage>> messages = new java.util.ArrayList<>();

        @Override
        public LlmResponse generate(List<LlmMessage> messages) {
            this.messages.add(List.copyOf(messages));
            return new TextLlmResponse("测试回答");
        }
    }

    // 按顺序返回工具调用和最终文本，用来验证召回工具确实经过 AgentLoop。
    private static class SequenceLlmClient implements LlmClient {

        private final Deque<LlmResponse> responses;
        private final List<List<LlmMessage>> messages = new ArrayList<>();

        private SequenceLlmClient(LlmResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public LlmResponse generate(List<LlmMessage> messages) {
            this.messages.add(List.copyOf(messages));
            return responses.removeFirst();
        }
    }
}
