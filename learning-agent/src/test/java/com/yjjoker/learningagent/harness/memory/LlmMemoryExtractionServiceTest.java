package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.*;
import com.yjjoker.learningagent.harness.memory.impl.LlmMemoryExtractionService;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.hook.AgentRunContext;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import tools.jackson.databind.json.JsonMapper;
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

    // 删除已完成后没有目标，修复反馈应要求移除该候选，同时保留其他独立事实。
    @Test
    void shouldRepairEmptyDeleteWithoutDroppingIndependentFact() {
        AgentRunContext run = new AgentRunContext();
        recordResult(run, true, ToolExecutionResult.memoryWriteSuccess("已删除运动偏好",
                new MemoryWriteReceipt(DELETE, USER, USER_ID, List.of(1L), List.of("sport"))));
        MemoryCandidate goal = candidate(CREATE, SESSION, List.of(), "本次学习事务", "goal", "学习事务");
        SequenceClient client = new SequenceClient(
                response(candidate(DELETE, USER, List.of(), "忘记运动偏好", null, null), goal),
                response(goal));

        var result = service(client).extract(contextWithTrace(run), "忘记运动偏好，本次学习事务", "已处理");

        assertEquals(1, result.size());
        assertEquals(SESSION, result.getFirst().getScope());
        assertEquals(2, client.requests.size());
        String feedback = client.requests.getLast().get(1).getContent();
        assertTrue(feedback.contains("移除此无目标候选"));
        assertTrue(feedback.contains("保留其他合法独立候选"));
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

    // 普通工具留在运行记录里，连名称和状态也不占用提取上下文。
    @Test
    void shouldProjectOrdinaryToolWithoutArgumentsOrBody() {
        AgentRunContext run = new AgentRunContext();
        recordResult(run, false, ToolExecutionResult.success("PRIVATE_BUSINESS_BODY"));
        SequenceClient client = new SequenceClient(response());
        service(client).extract(contextWithTrace(run), "查询资料", "查到了");
        String input = client.requests.getFirst().get(1).getContent();
        assertTrue(new JsonMapper().readTree(input).get("toolExecutions").isEmpty());
        assertFalse(input.contains("execution_1"));
        assertFalse(input.contains("PRIVATE_BUSINESS_BODY"));
        assertFalse(input.contains("PRIVATE_ARGUMENT"));
        assertFalse(input.contains("private_call_id"));
        assertEquals("PRIVATE_BUSINESS_BODY", run.getToolExecutions().getFirst().getResult().getContent());
    }

    // 记忆写结果只发送有限摘录；截断不能拆开表情，原始结果仍在本轮记录中。
    @Test
    void shouldBoundMemoryExcerptWithoutChangingOriginalResult() {
        AgentRunContext run = new AgentRunContext();
        String original = "记".repeat(999) + "😀" + "PRIVATE_TAIL";
        recordResult(run, true, committedResult(original));
        SequenceClient client = new SequenceClient(response());
        service(client).extract(contextWithTrace(run), "查询记忆", "查到了");
        var item = new JsonMapper().readTree(client.requests.getFirst().get(1).getContent())
                .get("toolExecutions").get(0);
        assertEquals(999, item.get("resultExcerpt").asString().length());
        assertTrue(item.get("resultTruncated").asBoolean());
        assertEquals(original.length(), item.get("resultCharacters").asInt());
        assertEquals(original, run.getToolExecutions().getFirst().getResult().getContent());
    }

    // 未执行的拒绝与执行后返回的业务失败是两种状态，错误正文不转发。
    @Test
    void shouldKeepRejectionAndBusinessFailureDistinct() {
        AgentRunContext run = new AgentRunContext();
        ToolCall rejected = new ToolCall("rejected", "query", "{}");
        run.requestToolExecution(rejected);
        run.classifyToolExecution(rejected, true);
        run.rejectToolExecution(rejected, ToolExecutionResult.failure("DENIED", "PRIVATE_ERROR", false));
        recordResult(run, true, ToolExecutionResult.failure("NOT_FOUND", "PRIVATE_ERROR", true));
        SequenceClient client = new SequenceClient(response());
        service(client).extract(contextWithTrace(run), "查询资料", "未查到");
        String input = client.requests.getFirst().get(1).getContent();
        var history = new JsonMapper().readTree(input).get("toolExecutions");
        assertEquals("REJECTED", history.get(0).get("status").asString());
        assertEquals("FAILED", history.get(1).get("status").asString());
        assertEquals("NOT_FOUND", history.get(1).get("errorCode").asString());
        assertFalse(input.contains("PRIVATE_ERROR"));
    }

    // 记忆工具返回的旧事实不能冒充本轮用户原话，通过后端校验而不只依赖提示词。
    @Test
    void shouldRejectEvidenceOnlyPresentInToolResult() {
        AgentRunContext run = new AgentRunContext();
        recordResult(run, true, committedResult("我喜欢羽毛球"));
        SequenceClient client = new SequenceClient(response(
                candidate(CREATE, USER, List.of(), "我喜欢羽毛球", "sport", "喜欢羽毛球")));
        assertThrows(MemoryExtractionFormatException.class,
                () -> service(client).extract(contextWithTrace(run), "查询旧记忆", "你喜欢羽毛球"));
        assertEquals(2, client.requests.size());
        // 格式修复始终复用相同的执行记录，不能中途换成另一轮的结果。
        assertTrue(client.requests.get(1).get(1).getContent().contains("execution_1"));
    }

    // 查询工具不会阻止提取用户在同一轮明确提供的新事实。
    @Test
    void shouldExtractNewUserFactAfterReadingMemory() {
        AgentRunContext run = new AgentRunContext();
        recordResult(run, false, ToolExecutionResult.success("喜欢羽毛球"));
        SequenceClient client = new SequenceClient(response(
                candidate(CREATE, USER, List.of(), "叫我小林", "nickname", "称呼为小林")));
        var candidates = service(client).extract(contextWithTrace(run), "查询旧记忆，以后叫我小林", "好的");
        assertEquals(1, candidates.size());
        assertEquals("nickname", candidates.getFirst().getMemoryKey());
    }

    // 超过数量上限时整轮停止提取，不能静默只保留前五十次操作。
    @Test
    void shouldRejectOversizedTraceBeforeCallingModel() {
        AgentRunContext run = new AgentRunContext();
        for (int index = 0; index < 51; index++) {
            recordResult(run, true, committedResult("ok"));
        }
        LlmClient client = mock(LlmClient.class);
        assertThrows(IllegalStateException.class,
                () -> service(client).extract(contextWithTrace(run), "问题", "回答"));
        verifyNoInteractions(client);
    }

    // 即使调用次数不多，大段记忆摘录累积也不能超过提取输入的字符预算。
    @Test
    void shouldRejectTraceCharacterOverflowBeforeCallingModel() {
        AgentRunContext run = new AgentRunContext();
        for (int index = 0; index < 12; index++) {
            recordResult(run, true, committedResult("记".repeat(1_000)));
        }
        LlmClient client = mock(LlmClient.class);
        assertThrows(IllegalStateException.class,
                () -> service(client).extract(contextWithTrace(run), "问题", "回答"));
        verifyNoInteractions(client);
    }

    // 查询再多也只保存在运行记录中，不触发写操作的数量和字符限制。
    @Test
    void shouldIgnoreReadOnlyCallsWhenApplyingExtractionBudget() {
        AgentRunContext run = new AgentRunContext();
        for (int index = 0; index < 60; index++) {
            recordResult(run, false, ToolExecutionResult.success("资料".repeat(5_000)));
        }
        SequenceClient client = new SequenceClient(response(
                candidate(CREATE, USER, List.of(), "叫我小林", "nickname", "称呼为小林")));
        assertEquals(1, service(client).extract(contextWithTrace(run), "叫我小林", "好的").size());
        assertEquals(60, run.getToolExecutions().size());
        assertTrue(new JsonMapper().readTree(client.requests.getFirst().get(1).getContent())
                .get("toolExecutions").isEmpty());
    }

    // 主循环旧引用与提取索引顺序不同，凭据必须按真实 ID 重新绑定当前引用。
    @Test
    void shouldRemapCommittedTargetsWithoutSendingDatabaseIds() {
        AgentRunContext run = new AgentRunContext();
        var receipt = new MemoryWriteReceipt(UPDATE, USER, USER_ID, List.of(900001L), List.of("sport"));
        recordResult(run, true, ToolExecutionResult.memoryWriteSuccess("已修改", receipt));
        var snapshot = new MemoryExtractionContext(USER_ID, SESSION_ID,
                new MemoryIndexSnapshot(List.of(user(900002L, "nickname", "小林"),
                        user(900001L, "sport", "喜欢篮球")), List.of()), run.getToolExecutions());
        SequenceClient client = new SequenceClient(response());
        service(client).extract(snapshot, "修改完成了吗", "完成了");
        String input = client.requests.getFirst().get(1).getContent();
        var json = new JsonMapper().readTree(input);
        var item = json.get("toolExecutions").get(0);
        assertEquals("memory_2", item.get("affectedMemoryRefs").get(0).asString());
        assertTrue(item.get("writeCommitted").asBoolean());
        assertEquals("USER", json.get("memoryWritePolicy").get("blockedCreateScopes").get(0).asString());
        assertEquals("memory_2", json.get("memoryWritePolicy").get("protectedMemoryRefs").get(0).asString());
        assertFalse(input.contains("900001"));
        assertFalse(input.contains("ownerId"));
    }

    // 只有 success=true 的自然语言结果不够，缺少凭据时模型不能补做写入。
    @Test
    void shouldRepairCandidateWhenWriteReceiptIsMissing() {
        AgentRunContext run = new AgentRunContext();
        recordResult(run, true, ToolExecutionResult.success("我已经保存了"));
        SequenceClient client = new SequenceClient(response(
                candidate(CREATE, USER, List.of(), "喜欢篮球", "sport", "喜欢篮球")), response());
        assertTrue(service(client).extract(contextWithTrace(run), "喜欢篮球", "好的").isEmpty());
        assertEquals(2, client.requests.size());
        var input = new JsonMapper().readTree(client.requests.getFirst().get(1).getContent());
        assertTrue(input.get("memoryWritePolicy").get("automaticWritesBlocked").asBoolean());
        assertFalse(input.get("toolExecutions").get(0).get("writeCommitted").asBoolean());
        assertTrue(client.requests.get(1).get(1).getContent().contains("不得自动补做"));
    }

    // 凭据不能通过普通工具响应暴露；事务没有结束时不能提前生成成功结果。
    @Test
    void shouldKeepReceiptInternalAndRejectSuccessBeforeCommit() {
        var receipt = new MemoryWriteReceipt(CREATE, USER, USER_ID, List.of(900001L), List.of("sport"));
        String json = new JsonMapper().writeValueAsString(ToolExecutionResult.memoryWriteSuccess("保存完成", receipt));
        assertFalse(json.contains("900001"));
        assertFalse(json.contains("writeReceipt"));
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> ToolExecutionResult.memoryWriteSuccess("尚未提交", receipt));
        } finally {
            // 清理模拟事务标记，避免影响后续测试。
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // 每个请求独占记录；保存快照后继续执行其他工具，不会改变已交给提取的快照。
    @Test
    void shouldIsolateRunsAndRejectPendingTrace() {
        AgentRunContext first = new AgentRunContext();
        AgentRunContext second = new AgentRunContext();
        recordResult(first, false, ToolExecutionResult.success("第一次"));
        var snapshot = contextWithTrace(first);
        recordResult(first, false, ToolExecutionResult.success("第二次"));
        assertEquals(1, snapshot.getToolExecutions().size());
        assertEquals(2, first.getToolExecutions().getLast().getSequence());
        assertTrue(second.getToolExecutions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getToolExecutions().clear());
        second.requestToolExecution(new ToolCall("pending", "query", "{}"));
        assertFalse(second.hasCompleteToolHistory());
        assertThrows(IllegalStateException.class, () -> contextWithTrace(second));
    }

    // 通过真实运行上下文建立一条已完成记录，避免绕过生产状态转换。
    private void recordResult(AgentRunContext run, boolean memoryWriteTool, ToolExecutionResult result) {
        ToolCall call = new ToolCall("private_call_id", "query", "PRIVATE_ARGUMENT");
        run.requestToolExecution(call);
        run.classifyToolExecution(call, memoryWriteTool);
        run.startToolExecution(call);
        run.completeToolExecution(call, result);
    }

    // 提取索引为空，测试只关注本轮工具记录。
    private MemoryExtractionContext contextWithTrace(AgentRunContext run) {
        return new MemoryExtractionContext(USER_ID, SESSION_ID,
                new MemoryIndexSnapshot(List.of(), List.of()), run.getToolExecutions());
    }

    // 测试使用合成的已提交目标，只验证约束，不修改真实数据库。
    private ToolExecutionResult committedResult(String content) {
        return ToolExecutionResult.memoryWriteSuccess(content,
                new MemoryWriteReceipt(CREATE, USER, USER_ID, List.of(1L), List.of("savedFact")));
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
