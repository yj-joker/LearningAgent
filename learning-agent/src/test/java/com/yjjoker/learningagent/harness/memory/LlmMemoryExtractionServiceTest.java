package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.*;
import com.yjjoker.learningagent.harness.memory.impl.LlmMemoryExtractionService;
import com.yjjoker.learningagent.harness.memory.model.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static com.yjjoker.learningagent.harness.memory.MemoryTestData.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryOperation.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryScope.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;

class LlmMemoryExtractionServiceTest {
    // 验证新增分别属于用户和当前会话，且都有本轮用户依据。
    @Test
    void shouldExtractCreateCandidatesForBothScopes() {
        SequenceClient client = new SequenceClient(response(
                candidate(CREATE, USER, List.of(), "我喜欢篮球", "sport", "喜欢篮球"),
                candidate(CREATE, SESSION, List.of(), "今天学 Java", "goal", "学习 Java")));
        var result = service(client).extract(emptyContext(), "我喜欢篮球，今天学 Java", "好的");
        assertEquals(2, result.size());
        assertEquals(USER, result.getFirst().getScope());
        assertEquals(SESSION, result.getLast().getScope());
    }

    // 验证提取请求只包含索引字段，不包含数据库 ID 和记忆正文。
    @Test
    void shouldSendIndexAndParseMultipleUpdateTargets() {
        var first = user(900001L, "favoriteSport", "最喜欢羽毛球");
        first.setMemoryContent("PRIVATE_BODY_NOT_SENT");
        var second = user(900002L, "userFavoriteSport", "最喜欢羽毛球");
        var snapshot = context(List.of(first, second), List.of(session(900003L, "goal", "学习 Java")));
        SequenceClient client = new SequenceClient(response(
                candidate(UPDATE, USER, List.of("memory_1", "memory_2"), "最喜欢足球", null, "最喜欢足球")));
        var result = service(client).extract(snapshot, "现在最喜欢足球", "好的");
        assertEquals(List.of("memory_1", "memory_2"), result.getFirst().getTargetMemoryRefs());
        String input = client.requests.getFirst().get(1).getContent();
        assertTrue(input.contains("favoriteSport"));
        assertTrue(input.contains("memory_3"));
        assertFalse(input.contains("PRIVATE_BODY_NOT_SENT"));
        assertFalse(input.contains("900001"));
        assertFalse(input.contains("userId"));
        assertTrue(client.requests.getFirst().getFirst().getContent().contains("助手回答只能辅助理解"));
    }

    // 删除不需要 key 或新内容，只需要当前索引中的目标和用户依据。
    @Test
    void shouldParseDeleteWithoutInventingContent() {
        SequenceClient client = new SequenceClient(response(
                candidate(DELETE, USER, List.of("memory_1"), "忘记运动偏好", null, null)));
        var result = service(client).extract(context(List.of(user(1, "sport", "喜欢篮球")), List.of()),
                "请忘记运动偏好", "好的");
        assertEquals(DELETE, result.getFirst().getOperation());
        assertEquals("", result.getFirst().getMemoryKey());
    }

    // 错误引用交给有限修复循环；修复时仍使用原来的索引和映射。
    @Test
    void shouldRepairUnknownReferenceUsingSameIndex() {
        var snapshot = context(List.of(user(1, "sport", "喜欢篮球")), List.of());
        SequenceClient client = new SequenceClient(
                response(candidate(DELETE, USER, List.of("memory_999"), "忘记运动偏好", null, null)),
                response(candidate(DELETE, USER, List.of("memory_1"), "忘记运动偏好", null, null)));
        var result = service(client).extract(snapshot, "忘记运动偏好", "好的");
        assertEquals(2, client.requests.size());
        assertEquals("memory_1", result.getFirst().getTargetMemoryRefs().getFirst());
        assertTrue(client.requests.get(1).get(1).getContent().contains("目标引用不存在"));
        assertTrue(client.requests.get(1).get(1).getContent().contains("sport"));
    }

    // 不允许把会话索引的引用用来修改用户长期记忆。
    @Test
    void shouldRejectWrongScope() {
        var snapshot = context(List.of(), List.of(session(1, "goal", "学习 Java")));
        assertInvalid(snapshot, candidate(DELETE, USER, List.of("memory_1"), "删除目标", null, null), "删除目标");
    }

    // 不允许把同一目标重复放进一个候选。
    @Test
    void shouldRejectDuplicateReferences() {
        var snapshot = context(List.of(user(1, "sport", "喜欢篮球")), List.of());
        assertInvalid(snapshot, candidate(DELETE, USER, List.of("memory_1", "memory_1"), "忘记", null, null), "忘记");
    }

    // 助手说出的偏好不能代替用户原话作为证据。
    @Test
    void shouldRejectEvidenceOnlyPresentInAssistantAnswer() {
        SequenceClient client = new SequenceClient(response(
                candidate(CREATE, USER, List.of(), "你喜欢羽毛球", "sport", "喜欢羽毛球")));
        assertThrows(MemoryExtractionFormatException.class,
                () -> service(client).extract(emptyContext(), "推荐运动", "你喜欢羽毛球"));
        assertEquals(2, client.requests.size());
    }

    // 已存在的准确 key 不能被伪装成新的 CREATE。
    @Test
    void shouldRejectCreateForExistingKey() {
        var snapshot = context(List.of(user(1, "sport", "喜欢篮球")), List.of());
        assertInvalid(snapshot, candidate(CREATE, USER, List.of(), "喜欢足球", "sport", "喜欢足球"), "喜欢足球");
    }

    // 不明确或没有新事实时允许返回空数组，不能强制生成记忆。
    @Test
    void shouldAcceptEmptyCandidates() {
        SequenceClient client = new SequenceClient(response());
        assertTrue(service(client).extract(emptyContext(), "你好", "你好").isEmpty());
    }

    // 连续格式错误到达上限后停止，避免无限调用模型。
    @Test
    void shouldStopAfterRepairLimit() {
        SequenceClient client = new SequenceClient("not-json");
        assertThrows(MemoryExtractionFormatException.class,
                () -> service(client).extract(emptyContext(), "问题", "回答"));
        assertEquals(2, client.requests.size());
    }

    // 网络或服务异常不进入格式修复，避免两层重试相乘。
    @Test
    void shouldNotRepairUnexpectedClientFailures() {
        LlmClient client = mock(LlmClient.class);
        when(client.generateWithoutTools(anyList())).thenThrow(new IllegalStateException("network unavailable"));
        assertThrows(IllegalStateException.class, () -> service(client).extract(emptyContext(), "问题", "回答"));
        verify(client, times(1)).generateWithoutTools(anyList());
    }

    // 缺少本轮输入时直接跳过，不浪费模型请求。
    @Test
    void shouldSkipBlankUserInput() {
        LlmClient client = mock(LlmClient.class);
        assertTrue(service(client).extract(emptyContext(), " ", "回答").isEmpty());
        verifyNoInteractions(client);
    }

    // 建立真实提取服务，只有模型响应由测试提供。
    private LlmMemoryExtractionService service(LlmClient client) {
        return new LlmMemoryExtractionService(client, new LlmRetryExecutor());
    }

    // 同一个无效候选重试两次都应失败，不允许流入保存阶段。
    private void assertInvalid(MemoryExtractionContext snapshot, MemoryCandidate candidate, String user) {
        SequenceClient client = new SequenceClient(response(candidate));
        assertThrows(MemoryExtractionFormatException.class, () -> service(client).extract(snapshot, user, "好的"));
        assertEquals(2, client.requests.size());
    }

    // 保存每次请求，按顺序返回预设模型结果。
    private static class SequenceClient implements LlmClient {
        private final List<String> responses;
        private final List<List<LlmMessage>> requests = new ArrayList<>();

        // 用可变参数描述首次结果和修复结果。
        private SequenceClient(String... responses) {
            this.responses = List.of(responses);
        }

        // 提取不应该进入普通工具调用入口。
        @Override
        public LlmResponse generate(List<LlmMessage> messages) {
            throw new AssertionError("提取只能使用无工具入口");
        }

        // 记录上下文并返回本次预设结果。
        @Override
        public LlmResponse generateWithoutTools(List<LlmMessage> messages) {
            assertEquals(2, messages.size());
            int index = Math.min(requests.size(), responses.size() - 1);
            requests.add(List.copyOf(messages));
            return new TextLlmResponse(responses.get(index));
        }
    }
}
