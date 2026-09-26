package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.client.AliyunLlmClient;
import com.yjjoker.learningagent.config.AliyunLlmProperties;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.memory.impl.LlmMemoryExtractionService;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.MemoryCandidatePersistenceService;
import com.yjjoker.learningagent.harness.memory.service.StructuredMemoryService;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.List;
import java.util.Set;
import static com.yjjoker.learningagent.harness.memory.MemoryTestData.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryOperation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 显式开启才调用真实阿里云；只发送合成测试数据，不修改用户数据库。
@EnabledIfEnvironmentVariable(named = "MEMORY_ALIYUN_TEST", matches = "true")
class MemoryTargetAliyunTest {
    // 不给用户消息指定 key，让真实模型根据旧索引识别两条同义目标。
    @Test
    void shouldIdentifyBothAliasesForUpdate() {
        var first = user(1, "favoriteSport", "用户最喜欢羽毛球");
        var second = user(2, "userFavoriteSport", "用户最喜欢的运动是羽毛球");
        var running = user(3, "runningFrequency", "用户每周跑步三次");
        var context = context(List.of(first, second, running), List.of());
        String input = "我现在最喜欢足球了，不再是羽毛球，请修改你记住的运动偏好。跑步的次数不变。";
        var candidates = extractor().extract(context, input, "好的，我了解你的新偏好。");
        assertEquals(1, candidates.size());
        var candidate = candidates.getFirst();
        assertEquals(UPDATE, candidate.getOperation());
        assertEquals(Set.of("memory_1", "memory_2"), Set.copyOf(candidate.getTargetMemoryRefs()));
        assertTrue(candidate.getMemoryContent().contains("足球"));
        // 模型选择的结果继续经过真实保存服务，但写入用测试实体承接。
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        when(store.lockUserMemory(USER_ID, 1L)).thenReturn(first);
        when(store.lockUserMemory(USER_ID, 2L)).thenReturn(second);
        new MemoryCandidatePersistenceService(store).persist(context, input, candidates);
        assertTrue(first.getMemoryContent().contains("足球"));
        assertEquals(first.getMemoryContent(), second.getMemoryContent());
        assertEquals("用户每周跑步三次", running.getMemoryContent());
    }

    // 用户没有提供任何编号，模型仍应找出全部同义记忆并保留跑步频率。
    @Test
    void shouldIdentifyBothAliasesForDelete() {
        var first = user(1, "favoriteSport", "用户最喜欢羽毛球");
        var second = user(2, "userFavoriteSport", "用户最喜欢的运动是羽毛球");
        var context = context(List.of(first, second, user(3, "runningFrequency", "每周跑步三次")), List.of());
        String input = "请忘记我最喜欢什么运动这件事，关于每周跑步次数的记忆保留。";
        var candidates = extractor().extract(context, input, "好的。");
        assertEquals(1, candidates.size());
        assertEquals(DELETE, candidates.getFirst().getOperation());
        assertEquals(Set.of("memory_1", "memory_2"), Set.copyOf(candidates.getFirst().getTargetMemoryRefs()));
        StructuredMemoryService store = mock(StructuredMemoryService.class);
        when(store.lockUserMemory(USER_ID, 1L)).thenReturn(first);
        when(store.lockUserMemory(USER_ID, 2L)).thenReturn(second);
        new MemoryCandidatePersistenceService(store).persist(context, input, candidates);
        verify(store).deleteUserMemory(USER_ID, 1L);
        verify(store).deleteUserMemory(USER_ID, 2L);
        verify(store, never()).deleteUserMemory(USER_ID, 3L);
    }

    // 助手自行补充的偏好不能变成新的用户记忆。
    @Test
    void shouldNotExtractAssistantOnlyPreference() {
        var result = extractor().extract(emptyContext(), "推荐一种适合周末的运动。", "你喜欢羽毛球，可以约朋友去打羽毛球。");
        assertTrue(result.isEmpty(), "助手回答不能单独成为用户偏好的来源");
    }

    // 用户没有明确要改哪件事、改成什么时，不猜测保存目标。
    @Test
    void shouldSkipAmbiguousChange() {
        var context = context(List.of(user(1, "sport", "喜欢羽毛球"), user(2, "nickname", "希望被称为小明")), List.of());
        var result = extractor().extract(context, "把之前说的那个修改一下。", "请说明你想修改什么。");
        assertTrue(result.isEmpty(), "目标不明确时应放弃变更");
    }

    // 使用现有项目客户端和修复循环，API Key 只从运行环境读取。
    private LlmMemoryExtractionService extractor() {
        AliyunLlmProperties properties = new AliyunLlmProperties();
        properties.setBaseUrl(env("ALIYUN_LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));
        properties.setModel(env("ALIYUN_LLM_MODEL", "qwen-plus"));
        properties.setApiKey(env("ALIYUN_LLM_API_KEY", System.getenv("ALIYUN_EMBEDDING_API_KEY")));
        assertNotNull(properties.getApiKey(), "真实测试需要模型密钥环境变量");
        return new LlmMemoryExtractionService(new AliyunLlmClient(properties, new ToolRegistry(List.of())), new LlmRetryExecutor());
    }

    // 使用环境配置或项目相同的默认值，不输出配置中的密钥。
    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
