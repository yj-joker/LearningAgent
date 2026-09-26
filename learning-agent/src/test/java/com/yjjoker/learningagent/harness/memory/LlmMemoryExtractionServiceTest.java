package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.harness.impl.LlmMemoryExtractionService;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmMemoryExtractionServiceTest {

    @Test
    void shouldParseUserAndSessionMemoryCandidates() {
        String response = """
                {"memories":[
                  {"scope":"USER","operation":"CREATE","memoryKey":"favorite_sport","memoryTopic":"preference","memorySummary":"用户喜欢篮球","memoryContent":"用户明确表示喜欢篮球。"},
                  {"scope":"SESSION","operation":"CREATE","memoryKey":"current_goal","memoryTopic":"task_state","memorySummary":"当前正在测试记忆提取","memoryContent":"本轮任务是验证候选记忆提取。"}
                ]}
                """;
        LlmMemoryExtractionService service = new LlmMemoryExtractionService(
                new FixedLlmClient(response),
                new LlmRetryExecutor()
        );

        List<MemoryCandidate> candidates = service.extract(10L, "我喜欢篮球", "已记录你的偏好");

        assertEquals(2, candidates.size());
        assertEquals(MemoryScope.USER, candidates.get(0).getScope());
        assertEquals("favorite_sport", candidates.get(0).getMemoryKey());
        assertEquals(MemoryScope.SESSION, candidates.get(1).getScope());
        assertEquals("current_goal", candidates.get(1).getMemoryKey());
    }

    @Test
    void shouldReturnEmptyListWhenModelFindsNoMemory() {
        LlmMemoryExtractionService service = new LlmMemoryExtractionService(
                new FixedLlmClient("{\"memories\":[]}"),
                new LlmRetryExecutor()
        );

        List<MemoryCandidate> candidates = service.extract(10L, "你好", "你好，有什么可以帮助你？");

        assertTrue(candidates.isEmpty());
    }

    @Test
    void shouldRejectInvalidCandidateShape() {
        LlmMemoryExtractionService service = new LlmMemoryExtractionService(
                new FixedLlmClient("{\"memories\":[{\"scope\":\"USER\",\"operation\":\"UNKNOWN\"}]}"),
                new LlmRetryExecutor()
        );

        assertThrows(IllegalStateException.class,
                () -> service.extract(10L, "问题", "回答"));
    }

    @Test
    void shouldRetryWhenFirstExtractionResponseHasInvalidShape() {
        SequenceLlmClient client = new SequenceLlmClient(List.of(
                "{\"wrong\":[]}",
                "{\"memories\":[{\"scope\":\"USER\",\"operation\":\"CREATE\",\"memoryKey\":\"favorite_sport\",\"memoryTopic\":\"preference\",\"memorySummary\":\"用户喜欢篮球\",\"memoryContent\":\"用户明确表示喜欢篮球。\"}]}"
        ));
        LlmMemoryExtractionService service = new LlmMemoryExtractionService(
                client,
                new LlmRetryExecutor()
        );

        List<MemoryCandidate> candidates = service.extract(10L, "我喜欢篮球", "已记录");

        assertEquals(1, candidates.size());
        assertEquals(2, client.callCount);
    }

    @Test
    void shouldStopAfterConfiguredExtractionRepairAttempts() {
        SequenceLlmClient client = new SequenceLlmClient(List.of(
                "{\"wrong\":[]}",
                "{\"stillWrong\":[]}"
        ));
        LlmMemoryExtractionService service = new LlmMemoryExtractionService(
                client,
                new LlmRetryExecutor()
        );

        assertThrows(IllegalStateException.class,
                () -> service.extract(10L, "问题", "回答"));
        assertEquals(2, client.callCount);
    }

    // 固定返回文本，确保测试只验证解析和校验，不访问真实模型网络。
    private static class FixedLlmClient implements LlmClient {

        private final String response;

        private FixedLlmClient(String response) {
            this.response = response;
        }

        @Override
        public LlmResponse generate(List<LlmMessage> messages) {
            return new TextLlmResponse(response);
        }

        @Override
        public LlmResponse generateWithoutTools(List<LlmMessage> messages) {
            // 记忆提取必须走无工具入口；测试同时保证收到的消息确实是两条。
            assertEquals(2, messages.size());
            return new TextLlmResponse(response);
        }
    }

    // 按顺序返回多次结果，验证格式修复循环是否真正再次请求模型。
    private static class SequenceLlmClient implements LlmClient {

        private final List<String> responses;
        private int callCount;

        private SequenceLlmClient(List<String> responses) {
            this.responses = responses;
        }

        @Override
        public LlmResponse generate(List<LlmMessage> messages) {
            return new TextLlmResponse("{\"memories\":[]}");
        }

        @Override
        public LlmResponse generateWithoutTools(List<LlmMessage> messages) {
            assertEquals(2, messages.size());
            return new TextLlmResponse(responses.get(Math.min(callCount++, responses.size() - 1)));
        }
    }
}
