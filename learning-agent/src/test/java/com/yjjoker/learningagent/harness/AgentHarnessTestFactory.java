package com.yjjoker.learningagent.harness;

import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.context.OriginalToolResultStore;
import com.yjjoker.learningagent.harness.hook.AgentHook;
import com.yjjoker.learningagent.harness.service.AgentHarnessServiceImpl;
import com.yjjoker.learningagent.harness.context.impl.InMemoryOriginalToolResultStoreImpl;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.memory.service.ConversationMemoryService;
import com.yjjoker.learningagent.harness.memory.model.MemoryCandidate;
import com.yjjoker.learningagent.harness.memory.service.MemoryCandidatePersistenceService;
import com.yjjoker.learningagent.harness.memory.service.MemoryExtractionService;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionContext;
import com.yjjoker.learningagent.harness.memory.service.MemoryReferenceRegistry;
import com.yjjoker.learningagent.harness.memory.service.StructuredMemoryService;
import com.yjjoker.learningagent.harness.service.AgentHarnessService;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import com.yjjoker.learningagent.repository.LearningSessionRepository;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 测试专用的 Harness 组装器，避免生产类暴露多个测试构造器。
public final class AgentHarnessTestFactory {

    private AgentHarnessTestFactory() {
        // 工厂只提供静态方法，不需要创建对象。
    }

    // 创建使用默认上下文限制和内存原文存储的基础 Harness。
    public static AgentHarnessService create(LlmClient llmClient,
                                             ToolRegistry toolRegistry,
                                             List<AgentHook> hooks,
                                             ConversationMemoryService conversationMemoryService,
                                             LearningSessionRepository sessionRepository) {
        return create(
                llmClient,
                toolRegistry,
                hooks,
                conversationMemoryService,
                sessionRepository,
                new ContextManager(40_000, 8_000),
                new InMemoryOriginalToolResultStoreImpl(),
                emptyStructuredMemoryService()
        );
    }

    // 创建可以替换上下文限制的 Harness，用于上下文压缩测试。
    public static AgentHarnessService create(LlmClient llmClient,
                                             ToolRegistry toolRegistry,
                                             List<AgentHook> hooks,
                                             ConversationMemoryService conversationMemoryService,
                                             LearningSessionRepository sessionRepository,
                                             ContextManager contextManager) {
        return create(
                llmClient,
                toolRegistry,
                hooks,
                conversationMemoryService,
                sessionRepository,
                contextManager,
                new InMemoryOriginalToolResultStoreImpl(),
                emptyStructuredMemoryService()
        );
    }

    // 创建可以替换工具原文存储的 Harness，用于恢复工具结果测试。
    public static AgentHarnessService create(LlmClient llmClient,
                                             ToolRegistry toolRegistry,
                                             List<AgentHook> hooks,
                                             ConversationMemoryService conversationMemoryService,
                                             LearningSessionRepository sessionRepository,
                                             ContextManager contextManager,
                                             OriginalToolResultStore resultStore) {
        return create(
                llmClient,
                toolRegistry,
                hooks,
                conversationMemoryService,
                sessionRepository,
                contextManager,
                resultStore,
                emptyStructuredMemoryService()
        );
    }

    // 创建可以注入自定义结构化记忆服务的 Harness，用于记忆索引测试。
    public static AgentHarnessService create(LlmClient llmClient,
                                             ToolRegistry toolRegistry,
                                             List<AgentHook> hooks,
                                             ConversationMemoryService conversationMemoryService,
                                             LearningSessionRepository sessionRepository,
                                             ContextManager contextManager,
                                             OriginalToolResultStore resultStore,
                                             StructuredMemoryService structuredMemoryService) {
        // 普通测试不需要直接创建工具依赖，因此这里统一创建一张引用表。
        return create(
                llmClient,
                toolRegistry,
                hooks,
                conversationMemoryService,
                sessionRepository,
                contextManager,
                resultStore,
                structuredMemoryService,
                new MemoryReferenceRegistry()
        );
    }

    // 记忆召回工具测试需要和 Harness 共用同一张引用表，因此允许测试显式传入。
    public static AgentHarnessService create(LlmClient llmClient,
                                             ToolRegistry toolRegistry,
                                             List<AgentHook> hooks,
                                             ConversationMemoryService conversationMemoryService,
                                             LearningSessionRepository sessionRepository,
                                             ContextManager contextManager,
                                             OriginalToolResultStore resultStore,
                                             StructuredMemoryService structuredMemoryService,
                                             MemoryReferenceRegistry referenceRegistry) {
        // 测试仍然使用生产完整构造器，确保依赖关系和真实运行一致。
        return new AgentHarnessServiceImpl(
                llmClient,
                toolRegistry,
                hooks,
                conversationMemoryService,
                sessionRepository,
                contextManager,
                resultStore,
                null,
                new LlmRetryExecutor(),
                structuredMemoryService,
                referenceRegistry,
                new NoopMemoryExtractionService(),
                new MemoryCandidatePersistenceService(structuredMemoryService)
        );
    }

    // 现有 Harness 循环测试只关注工具流程，不额外消耗一次模型响应。
    private static class NoopMemoryExtractionService implements MemoryExtractionService {

        @Override
        // 普通循环测试不提取记忆，避免额外消耗假模型响应。
        public List<MemoryCandidate> extract(MemoryExtractionContext context,
                                              String userMessage,
                                              String assistantAnswer) {
            return List.of();
        }
    }

    // 默认测试不关注结构化记忆时，返回两个空索引。
    private static StructuredMemoryService emptyStructuredMemoryService() {
        StructuredMemoryService service = mock(StructuredMemoryService.class);
        when(service.loadUserMemoryIndex(anyLong())).thenReturn(List.of());
        when(service.loadSessionMemoryIndex(anyLong())).thenReturn(List.of());
        return service;
    }
}
