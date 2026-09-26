package com.yjjoker.learningagent.harness;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.context.impl.InMemoryOriginalToolResultStoreImpl;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.memory.impl.LlmMemoryExtractionService;
import com.yjjoker.learningagent.harness.memory.service.MemoryCandidatePersistenceService;
import com.yjjoker.learningagent.harness.service.AgentHarnessServiceImpl;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.memory.service.ConversationMemoryService;
import com.yjjoker.learningagent.harness.memory.service.MemoryReferenceRegistry;
import com.yjjoker.learningagent.harness.memory.service.StructuredMemoryService;
import com.yjjoker.learningagent.harness.service.AgentHarnessService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

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

    // 主循环结束后使用最新索引提取，不能沿用回答开始时的旧映射。
    @Test
    void shouldExtractAndPersistWithFreshIndexAfterAnswer() {
        BaseContext.setCurrentId(USER_ID);
        UserMemory oldMemory = indexedMemory(1L, "oldSport");
        UserMemory currentMemory = indexedMemory(2L, "favoriteSport");
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        // 第一次加载供主模型回答，第二次加载供后置提取使用。
        when(store.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(oldMemory), List.of(currentMemory));
        when(store.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of());
        when(store.lockUserMemory(USER_ID, 2L)).thenReturn(currentMemory);
        LlmClient client = mock(LlmClient.class);
        when(client.generate(anyList())).thenReturn(new TextLlmResponse("了解你的新偏好。"));
        when(client.generateWithoutTools(anyList())).thenReturn(new TextLlmResponse("""
                {"memories":[{"scope":"USER","operation":"UPDATE",
                "targetMemoryRefs":["memory_1"],"userEvidence":"现在最喜欢足球",
                "memoryTopic":"运动偏好","memorySummary":"最喜欢足球","memoryContent":"现在最喜欢足球"}]}
                """));
        ConversationMemoryService history = mock(ConversationMemoryService.class);

        String answer = extractionHarness(client, store, history).run(SESSION_ID, "现在最喜欢足球");

        assertEquals("了解你的新偏好。", answer);
        assertEquals("现在最喜欢足球", currentMemory.getMemoryContent());
        assertEquals("favoriteSport", currentMemory.getMemoryKey());
        verify(store).updateUserMemory(currentMemory);
        verify(store, never()).lockUserMemory(USER_ID, 1L);
    }

    // 引用修复失败时仍返回主模型的回答，不允许错误候选进入数据库。
    @Test
    void shouldKeepAnswerWhenExtractionReferencesRemainInvalid() {
        BaseContext.setCurrentId(USER_ID);
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        when(store.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(indexedMemory(1L, "sport")));
        when(store.loadSessionMemoryIndex(SESSION_ID)).thenReturn(List.of());
        LlmClient client = mock(LlmClient.class);
        when(client.generate(anyList())).thenReturn(new TextLlmResponse("这是正常回答。"));
        when(client.generateWithoutTools(anyList())).thenReturn(new TextLlmResponse("""
                {"memories":[{"scope":"USER","operation":"DELETE",
                "targetMemoryRefs":["memory_999"],"userEvidence":"忘记运动"}]}
                """));

        String answer = extractionHarness(client, store, mock(ConversationMemoryService.class))
                .run(SESSION_ID, "忘记运动");

        assertEquals("这是正常回答。", answer);
        verify(client, org.mockito.Mockito.times(2)).generateWithoutTools(anyList());
        verify(store, never()).updateUserMemory(any());
        verify(store, never()).deleteUserMemory(any(), any());
    }

    // 使用生产构造器接入真实提取和保存服务，模型和数据库由测试替代。
    private AgentHarnessService extractionHarness(LlmClient client, StructuredMemoryService store,
                                                  ConversationMemoryService history) {
        LearningSessionRepository repository = mock(LearningSessionRepository.class);
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(repository.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        LlmRetryExecutor retry = new LlmRetryExecutor();
        return new AgentHarnessServiceImpl(client, new ToolRegistry(List.of()), List.of(), history, repository,
                new ContextManager(40_000, 8_000), new InMemoryOriginalToolResultStoreImpl(), null, retry,
                store, new MemoryReferenceRegistry(), new LlmMemoryExtractionService(client, retry),
                new MemoryCandidatePersistenceService(store));
    }

    // 创建属于当前用户的运动记忆，供主循环与提取索引使用。
    private UserMemory indexedMemory(Long id, String key) {
        UserMemory memory = new UserMemory();
        memory.setId(id);
        memory.setUserId(USER_ID);
        memory.setMemoryKey(key);
        memory.setMemoryTopic("运动偏好");
        memory.setMemorySummary("最喜欢羽毛球");
        memory.setMemoryContent("最喜欢羽毛球");
        return memory;
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
