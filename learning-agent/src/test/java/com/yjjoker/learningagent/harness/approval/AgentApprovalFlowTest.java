package com.yjjoker.learningagent.harness.approval;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.context.impl.InMemoryOriginalToolResultStoreImpl;
import com.yjjoker.learningagent.harness.hook.*;
import com.yjjoker.learningagent.harness.llm.*;
import com.yjjoker.learningagent.harness.llm.model.*;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.*;
import com.yjjoker.learningagent.harness.model.AgentRunStatus;
import com.yjjoker.learningagent.harness.service.AgentHarnessService;
import com.yjjoker.learningagent.harness.service.AgentHarnessServiceImpl;
import com.yjjoker.learningagent.harness.tool.*;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.*;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.AgentRunResult;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 用确定性的模型响应走真实 Harness 和审批服务；仓库是替身，不冒充真实 MySQL 或阿里云测试。
class AgentApprovalFlowTest {
    private final AgentApprovalRepository repository = mock(AgentApprovalRepository.class);
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final ConversationMemoryService history = mock(ConversationMemoryService.class);
    private final StructuredMemoryService memory = mock(StructuredMemoryService.class);
    private final MemoryExtractionService extraction = mock(MemoryExtractionService.class);
    private final MemoryConsolidationService consolidation = mock(MemoryConsolidationService.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final MemoryReferenceRegistry references = new MemoryReferenceRegistry();
    private final AgentApprovalService approvals = new AgentApprovalService(repository, sessions, history);
    // 字符串检查点真的经过序列化和反序列化，不直接复用暂停时的 Java 对象。
    private final Map<String, AgentApprovalRun> runs = new HashMap<>();
    private final List<ToolApprovalRequest> requests = new ArrayList<>();
    private AgentHarnessService harness;

    // 建立登录范围和仓库存取行为，让测试只控制模型下一步和用户审批决定。
    @BeforeEach
    void setup() {
        BaseContext.setCurrentId(7L);
        LearningSession session = new LearningSession();
        session.setId(9L);
        session.setUserId(7L);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(9L)).thenReturn(Optional.of(session));
        when(memory.loadUserMemoryIndex(7L)).thenReturn(List.of());
        when(memory.loadSessionMemoryIndex(9L)).thenReturn(List.of());
        when(history.loadHistory(9L)).thenReturn(List.of(LlmMessage.user("我叫小明"), LlmMessage.assistant("你好，小明")));
        when(repository.insertRun(any())).thenAnswer(inv -> {
            AgentApprovalRun run = inv.getArgument(0);
            runs.put(run.getRunId(), run);
            return 1;
        });
        when(repository.pauseAgain(any())).thenAnswer(inv -> {
            AgentApprovalRun run = inv.getArgument(0);
            if (runs.get(run.getRunId()).getStatus() != AgentRunStatus.RUNNING) return 0;
            runs.put(run.getRunId(), run);
            return 1;
        });
        when(repository.insertApproval(any())).thenAnswer(inv -> { requests.add(inv.getArgument(0)); return 1; });
        when(repository.find(anyString(), anyLong())).thenAnswer(inv -> owned(inv.getArgument(0), inv.getArgument(1)));
        when(repository.lock(anyString(), anyLong())).thenAnswer(inv -> owned(inv.getArgument(0), inv.getArgument(1)));
        when(repository.approvals(anyString(), anyInt())).thenAnswer(inv -> batch(inv.getArgument(0), inv.getArgument(1)));
        when(repository.pending(anyString(), anyInt())).thenAnswer(inv -> batch(inv.getArgument(0), inv.getArgument(1))
                .stream().filter(r -> "PENDING".equals(r.getStatus())).map(ToolApprovalRequest::getToolCallId).toList());
        when(repository.decide(anyString(), anyInt(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            for (ToolApprovalRequest request : batch(inv.getArgument(0), inv.getArgument(1))) {
                if (request.getToolCallId().equals(inv.getArgument(2)) && "PENDING".equals(request.getStatus())) {
                    request.setStatus(inv.getArgument(3));
                    request.setDecisionReason(inv.getArgument(4));
                    return 1;
                }
            }
            return 0;
        });
        when(repository.transition(anyString(), anyLong(), anyString(), anyString())).thenAnswer(inv -> {
            AgentApprovalRun run = owned(inv.getArgument(0), inv.getArgument(1));
            if (run == null || !run.getStatus().name().equals(inv.getArgument(2))) return 0;
            run.setStatus(AgentRunStatus.valueOf(inv.getArgument(3)));
            return 1;
        });
        when(repository.complete(anyString(), anyLong(), anyString())).thenAnswer(inv -> {
            AgentApprovalRun run = owned(inv.getArgument(0), inv.getArgument(1));
            if (run == null || run.getStatus() != AgentRunStatus.RUNNING) return 0;
            run.setAnswer(inv.getArgument(2));
            run.setStatus(AgentRunStatus.COMPLETED);
            // 对应生产 SQL 的 JSON_OBJECT()；状态和答案保留，但正文已清空。
            run.setCheckpointJson("{}");
            return 1;
        });
        when(repository.unfinished(anyLong(), anyLong())).thenAnswer(inv -> (int) runs.values().stream()
                .filter(r -> r.getUserId().equals(inv.getArgument(0)) && r.getSessionId().equals(inv.getArgument(1)))
                .filter(r -> Set.of(AgentRunStatus.WAITING_APPROVAL, AgentRunStatus.APPROVAL_RESOLVED,
                        AgentRunStatus.RUNNING).contains(r.getStatus())).count());
    }

    // 不把认证或短引用留给下一个测试。
    @AfterEach
    void clear() { BaseContext.removeCurrentId(); references.clear(); }

    // 非记忆工具也能直接接入审批；保存申请后没有第二次模型调用、工具执行或后置提取。
    @Test
    void pausesNonMemoryToolWithoutExecutingAnything() {
        AtomicInteger calls = new AtomicInteger();
        build(List.of(tool("change_setting", true, calls)), List.of());
        when(llm.generate(any())).thenReturn(response("a", "change_setting"));
        AgentRunResult result = harness.run(9L, "调整设置");
        assertEquals(AgentRunStatus.WAITING_APPROVAL, result.getStatus());
        assertEquals(1, result.getBatchNumber());
        assertEquals("change_setting", result.getApprovals().getFirst().getToolName());
        assertEquals(0, calls.get());
        verify(llm, times(1)).generate(any());
        verify(history, never()).appendMessages(any(), any());
        verifyNoInteractions(extraction, consolidation);
        AgentRunCheckpoint saved = approvals.restore(runs.get(result.getRunId()));
        assertEquals("a", saved.getMessages().getLast().getToolCalls().getFirst().id());
        assertNull(references.currentUserId());
    }

    // 换一次执行后保留历史、用户问题和原 callId；先执行工具，才调用模型组织答案。
    @Test
    void resumesOriginalCallsAndDoesNotReloadHistoryOrRepeatExecution() {
        AtomicInteger calls = new AtomicInteger();
        build(List.of(tool("change_setting", true, calls)), List.of());
        when(llm.generate(any())).thenReturn(response("original-id", "change_setting"), new TextLlmResponse("已完成"));
        AgentRunResult paused = harness.run(9L, "调整设置");
        approveAll(paused);
        assertEquals(0, calls.get());
        AgentRunResult result = harness.resume(paused.getRunId());
        assertEquals(paused.getRunId(), result.getRunId());
        assertEquals("已完成", result.getAnswer());
        assertEquals("{}", runs.get(paused.getRunId()).getCheckpointJson());
        assertEquals(1, calls.get());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).generate(sent.capture());
        List<LlmMessage> restored = sent.getAllValues().getLast();
        assertTrue(restored.stream().anyMatch(m -> "我叫小明".equals(m.getContent())));
        assertEquals(1, restored.stream().filter(m -> "调整设置".equals(m.getContent())).count());
        assertEquals(1, restored.stream().filter(m -> !m.getToolCalls().isEmpty()).count());
        LlmMessage tool = restored.stream().filter(m -> "tool".equals(m.getRole())).findFirst().orElseThrow();
        assertEquals("original-id", tool.getToolCallId());
        assertTrue(tool.getContent().contains("success"));
        assertEquals("已完成", harness.resume(paused.getRunId()).getAnswer());
        assertEquals(1, calls.get());
        verify(llm, times(2)).generate(any());
        verify(history, times(1)).loadHistory(9L);
        verify(history, times(1)).appendMessages(eq(9L), any());
    }

    // 用户拒绝也要补一条同 ID 的 tool 结果，不能让 assistant 的工具请求悬空。
    @Test
    void returnsUserRejectionToModelWithoutExecutingTool() {
        AtomicInteger calls = new AtomicInteger();
        build(List.of(tool("change_setting", true, calls)), List.of());
        when(llm.generate(any())).thenReturn(response("a", "change_setting"), new TextLlmResponse("未修改"));
        AgentRunResult paused = harness.run(9L, "调整设置");
        approvals.decide(paused.getRunId(), 1, "a", false, "暂不修改");
        harness.resume(paused.getRunId());
        assertEquals(0, calls.get());
        ArgumentCaptor<List<LlmMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).generate(messages.capture());
        assertTrue(messages.getValue().stream().anyMatch(m -> "a".equals(m.getToolCallId())
                && m.getContent().contains("APPROVAL_REJECTED")));
    }

    // 普通工具排在待审批工具前后都先暂停；部分批准时不能恢复或创建新聊天。
    @Test
    void holdsMixedBatchUntilEveryApprovalHasDecision() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        build(List.of(tool("read", false, reads), tool("write", true, writes)), List.of());
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(new ToolCall("r", "read", "{}"),
                new ToolCall("w1", "write", "{}"), new ToolCall("w2", "write", "{}"))), new TextLlmResponse("完成"));
        AgentRunResult paused = harness.run(9L, "查询后修改");
        approvals.decide(paused.getRunId(), 1, "w1", true, null);
        assertThrows(RuntimeException.class, () -> harness.resume(paused.getRunId()));
        assertThrows(RuntimeException.class, () -> harness.run(9L, "另一个问题"));
        assertEquals(0, reads.get() + writes.get());
        approvals.decide(paused.getRunId(), 1, "w2", false, null);
        harness.resume(paused.getRunId());
        assertEquals(1, reads.get());
        assertEquals(1, writes.get());
    }

    // 审批 Hook 先提出要求，后面的权限 Hook 仍然可以拒绝，不能靠用户批准绕过。
    @Test
    void denialAfterApprovalHookPreventsWholeBatch() {
        AtomicInteger calls = new AtomicInteger();
        build(List.of(tool("change_setting", true, calls)), List.of(denyHook(new AtomicBoolean(true))));
        when(llm.generate(any())).thenReturn(response("a", "change_setting"));
        AgentRunResult result = harness.run(9L, "调整设置");
        assertEquals(AgentRunStatus.COMPLETED, result.getStatus());
        assertEquals("无权执行", result.getAnswer());
        assertEquals(0, calls.get());
        assertTrue(runs.isEmpty());
    }

    // 等待期间权限变化，恢复时再次检查；已经批准也不能执行。
    @Test
    void rechecksPermissionAfterApproval() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean denied = new AtomicBoolean(false);
        build(List.of(tool("change_setting", true, calls)), List.of(denyHook(denied)));
        when(llm.generate(any())).thenReturn(response("a", "change_setting"));
        AgentRunResult paused = harness.run(9L, "调整设置");
        approveAll(paused);
        denied.set(true);
        assertEquals("无权执行", harness.resume(paused.getRunId()).getAnswer());
        assertEquals(0, calls.get());
        verify(llm, times(1)).generate(any());
    }

    // 同一任务可以再次暂停，但批次号递增，旧批准不能用于下一批。
    @Test
    void startsNewBatchWithoutChangingRunId() {
        AtomicInteger calls = new AtomicInteger();
        build(List.of(tool("change_setting", true, calls)), List.of());
        when(llm.generate(any())).thenReturn(response("a", "change_setting"), response("b", "change_setting"),
                new TextLlmResponse("两次完成"));
        AgentRunResult first = harness.run(9L, "执行两步");
        approveAll(first);
        AgentRunResult second = harness.resume(first.getRunId());
        assertEquals(first.getRunId(), second.getRunId());
        assertEquals(2, second.getBatchNumber());
        assertEquals(1, calls.get());
        assertThrows(RuntimeException.class, () -> approvals.decide(first.getRunId(), 1, "a", true, null));
        approveAll(second);
        assertEquals("两次完成", harness.resume(first.getRunId()).getAnswer());
        assertEquals(2, calls.get());
    }

    // 抢占之后第二个恢复请求会被拒绝，不靠 Java 单机锁实现这个边界。
    @Test
    void disallowsSecondClaimAndForeignAccess() {
        build(List.of(tool("write", true, new AtomicInteger())), List.of());
        when(llm.generate(any())).thenReturn(response("a", "write"));
        AgentRunResult paused = harness.run(9L, "执行");
        BaseContext.setCurrentId(88L);
        assertThrows(RuntimeException.class, () -> approvals.get(paused.getRunId()));
        assertThrows(RuntimeException.class, () -> approvals.decide(paused.getRunId(), 1, "a", true, null));
        BaseContext.setCurrentId(7L);
        approveAll(paused);
        approvals.claim(paused.getRunId());
        assertThrows(RuntimeException.class, () -> approvals.claim(paused.getRunId()));
    }

    // 所有工具都成功但模型请求失败时，不允许通过再次 resume 重复执行工具。
    @Test
    void doesNotReplayToolsAfterUnknownFailure() {
        AtomicInteger calls = new AtomicInteger();
        build(List.of(tool("write", true, calls)), List.of());
        when(llm.generate(any())).thenReturn(response("a", "write")).thenThrow(new IllegalStateException("模拟模型异常"));
        AgentRunResult paused = harness.run(9L, "执行");
        approveAll(paused);
        assertThrows(RuntimeException.class, () -> harness.resume(paused.getRunId()));
        assertEquals(AgentRunStatus.FAILED, runs.get(paused.getRunId()).getStatus());
        assertThrows(RuntimeException.class, () -> harness.resume(paused.getRunId()));
        assertEquals(1, calls.get());
    }

    // 数据落库失败不能返回“已提交审批”。
    @Test
    void failsPauseWhenRequestCannotBeSaved() {
        build(List.of(tool("write", true, new AtomicInteger())), List.of());
        when(llm.generate(any())).thenReturn(response("a", "write"));
        when(repository.insertApproval(any())).thenReturn(0);
        assertThrows(RuntimeException.class, () -> harness.run(9L, "执行"));
        verify(llm, times(1)).generate(any());
    }

    // 真正使用 Spring 事务拦截器，申请保存失败必须对连接发出 rollback；连接仍是测试替身。
    @Test
    void rollsBackWhenSavingAnApprovalFails() throws Exception {
        java.sql.Connection connection = mock(java.sql.Connection.class);
        AgentApprovalService transactional = transactionalService(connection);
        when(repository.insertApproval(any())).thenReturn(0);
        assertThrows(RuntimeException.class, () -> transactional.pause(checkpoint(), Map.of("a", "确认")));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    // 申请和检查点全部保存后才提交，不单独提交其中一部分。
    @Test
    void commitsCheckpointAndApprovalsTogether() throws Exception {
        java.sql.Connection connection = mock(java.sql.Connection.class);
        AgentApprovalService transactional = transactionalService(connection);
        assertEquals(AgentRunStatus.WAITING_APPROVAL, transactional.pause(checkpoint(), Map.of("a", "确认")).getStatus());
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    // 最终聊天保存失败时，完成状态和正文清理也必须回滚，避免尚未保存历史就丢失检查点。
    @Test
    void rollsBackCompletionWhenHistoryCannotBeSaved() throws Exception {
        build(List.of(tool("write", true, new AtomicInteger())), List.of());
        when(llm.generate(any())).thenReturn(response("a", "write"));
        AgentRunResult paused = harness.run(9L, "执行");
        approveAll(paused);
        approvals.claim(paused.getRunId());
        java.sql.Connection connection = mock(java.sql.Connection.class);
        AgentApprovalService transactional = transactionalService(connection);
        doThrow(new IllegalStateException("模拟保存失败")).when(history).appendMessages(any(), any());
        assertThrows(RuntimeException.class, () -> transactional.complete(paused.getRunId(), 9L,
                List.of(LlmMessage.user("执行"), LlmMessage.assistant("完成")), "完成"));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    // 核对实际 Mapper SQL，不能只让仓库替身清空正文，生产语句却漏掉清理。
    @Test
    void completionSqlClearsOnlyRunningCheckpoint() throws Exception {
        var method = AgentApprovalRepository.class.getMethod("complete", String.class, Long.class, String.class);
        String sql = String.join(" ", method.getAnnotation(org.apache.ibatis.annotations.Update.class).value());
        assertTrue(sql.contains("checkpoint_json=JSON_OBJECT()"));
        assertTrue(sql.contains("status='RUNNING'"));
        assertTrue(sql.contains("answer=#{answer}"));
    }

    // 服务端核对原参数，不能批准之后用被替换的参数执行工具。
    @Test
    void rejectsApprovalWhoseArgumentsDoNotMatchCheckpoint() {
        AtomicInteger calls = new AtomicInteger();
        build(List.of(tool("write", true, calls)), List.of());
        when(llm.generate(any())).thenReturn(response("a", "write"));
        AgentRunResult paused = harness.run(9L, "执行");
        approveAll(paused);
        requests.getFirst().setArguments("{\"changed\":true}");
        assertThrows(RuntimeException.class, () -> harness.resume(paused.getRunId()));
        assertEquals(0, calls.get());
    }

    // 暂停不能刷新五轮上限，恢复后的下一次模型工具请求仍受原预算约束。
    @Test
    void retainsToolRoundBudgetAcrossResume() {
        AtomicInteger calls = new AtomicInteger();
        build(List.of(tool("write", true, calls)), List.of());
        when(llm.generate(any())).thenReturn(response("a", "write"), response("b", "write"));
        AgentRunResult paused = harness.run(9L, "执行");
        AgentApprovalRun run = runs.get(paused.getRunId());
        AgentRunCheckpoint checkpoint = approvals.restore(run);
        checkpoint.setCompletedToolRounds(4);
        run.setCheckpointJson(new JsonMapper().writeValueAsString(checkpoint));
        approveAll(paused);
        assertThrows(IllegalStateException.class, () -> harness.resume(paused.getRunId()));
        assertEquals(1, calls.get());
    }

    // 只测试事务边界，不把模拟 JDBC 连接的 commit 当成真实 MySQL 已落库。
    private AgentApprovalService transactionalService(java.sql.Connection connection) throws Exception {
        javax.sql.DataSource source = mock(javax.sql.DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        org.springframework.aop.framework.ProxyFactory factory = new org.springframework.aop.framework.ProxyFactory(approvals);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (AgentApprovalService) factory.getProxy();
    }

    // 给事务测试提供最小合法快照，工具尚未执行。
    private AgentRunCheckpoint checkpoint() {
        AgentRunCheckpoint checkpoint = new AgentRunCheckpoint();
        checkpoint.setRunId(UUID.randomUUID().toString());
        checkpoint.setUserId(7L);
        checkpoint.setSessionId(9L);
        checkpoint.setUserMessage("执行");
        checkpoint.setPendingCalls(List.of(new ToolCall("a", "write", "{}")));
        checkpoint.setMessages(List.of(LlmMessage.user("执行"), LlmMessage.assistantToolCalls(checkpoint.getPendingCalls())));
        return checkpoint;
    }


    // 等待期间另一个工具新增审批要求时，不丢弃已有拒绝或直接放行整批。
    @Test
    void stopsWhenNewApprovalRequirementAppearsDuringWaiting() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean stricter = new AtomicBoolean(false);
        AgentHook changingPolicy = new AgentHook() {
            // 让原本不需要审批的普通工具在恢复时需要确认。
            public ToolCallHookResult beforeToolExecution(AgentRunContext context, ToolCall call) {
                return stricter.get() && "read".equals(call.name())
                        ? ToolCallHookResult.requireApproval("新规则要求确认") : ToolCallHookResult.allow();
            }
        };
        build(List.of(tool("write", true, calls), tool("read", false, calls)), List.of(changingPolicy));
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(
                new ToolCall("w", "write", "{}"), new ToolCall("r", "read", "{}"))));
        AgentRunResult paused = harness.run(9L, "执行");
        approvals.decide(paused.getRunId(), 1, "w", false, null);
        stricter.set(true);
        assertTrue(harness.resume(paused.getRunId()).getAnswer().contains("规则已变化"));
        assertEquals(0, calls.get());
        verify(llm, times(1)).generate(any());
    }


    // 使用真实的记忆工具和业务服务；只有仓库与写入事务是替身，验证接线没有遗漏。
    @Test
    void writesMemoryOnlyAfterApprovalAndResume() {
        MemoryCandidatePersistenceService persistence = mock(MemoryCandidatePersistenceService.class);
        MemoryToolService service = new MemoryToolService(references, memory, sessions, persistence);
        var create = new com.yjjoker.learningagent.harness.tool.impl.CreateMemoryTool(service);
        build(List.of(create), List.of());
        String evidence = "请记住我在学Java";
        String input = new JsonMapper().writeValueAsString(Map.of("scope", "USER", "memoryKey", "learningLanguage",
                "memoryTopic", "学习方向", "memorySummary", "学习Java", "memoryContent", "用户正在学习Java", "userEvidence", evidence));
        when(persistence.persistToolCandidate(any(), eq(evidence), any())).thenReturn(
                new MemoryWriteReceipt(MemoryOperation.CREATE, MemoryScope.USER, 7L, List.of(42L), List.of("learningLanguage")));
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(new ToolCall("create-original", create.name(), input))),
                new TextLlmResponse("已保存学习方向"));
        AgentRunResult paused = harness.run(9L, evidence);
        verifyNoInteractions(persistence);
        approveAll(paused);
        verifyNoInteractions(persistence);
        assertEquals("已保存学习方向", harness.resume(paused.getRunId()).getAnswer());
        verify(persistence, times(1)).persistToolCandidate(any(), eq(evidence), any());
    }

    // 等待后实际写入发现旧目标已变，返回失败工具结果，不能把批准当成写入成功。
    @Test
    void returnsMemoryConflictInsteadOfClaimingApprovedWriteSucceeded() {
        MemoryCandidatePersistenceService persistence = mock(MemoryCandidatePersistenceService.class);
        MemoryToolService service = new MemoryToolService(references, memory, sessions, persistence);
        var create = new com.yjjoker.learningagent.harness.tool.impl.CreateMemoryTool(service);
        build(List.of(create), List.of());
        String evidence = "请记住我在学Java";
        String input = new JsonMapper().writeValueAsString(Map.of("scope", "USER", "memoryKey", "learningLanguage",
                "memoryTopic", "学习方向", "memorySummary", "学习Java", "memoryContent", "用户正在学习Java", "userEvidence", evidence));
        when(persistence.persistToolCandidate(any(), eq(evidence), any()))
                .thenThrow(new com.yjjoker.learningagent.exception.ClientDataErrorException("模拟同key已存在"));
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(new ToolCall("a", create.name(), input))),
                new TextLlmResponse("记忆已变化，需要重新确认"));
        AgentRunResult paused = harness.run(9L, evidence);
        approveAll(paused);
        harness.resume(paused.getRunId());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).generate(sent.capture());
        assertTrue(sent.getValue().stream().anyMatch(m -> "tool".equals(m.getRole())
                && m.getContent().contains("MEMORY_TARGET_CHANGED")));
        // 把失败记录交给后置提取；候选校验会据此阻止自动补写，而不是把失败当成未调用。
        ArgumentCaptor<MemoryExtractionContext> extractionContext = ArgumentCaptor.forClass(MemoryExtractionContext.class);
        verify(extraction).extract(extractionContext.capture(), eq(evidence), anyString());
        assertTrue(extractionContext.getValue().hasUnconfirmedMemoryWrites());
    }


    // 组装生产服务与 Hook；测试替身只放测试代码，生产类不增加测试构造器。
    private void build(List<Tool> tools, List<AgentHook> extra) {
        ToolRegistry registry = new ToolRegistry(tools);
        List<AgentHook> hooks = new ArrayList<>(List.of(new ToolApprovalHook(registry), new ToolExecutionRecordingHook()));
        hooks.addAll(extra);
        harness = new AgentHarnessServiceImpl(llm, registry, hooks, history, sessions,
                new ContextManager(40_000, 8_000), new InMemoryOriginalToolResultStoreImpl(), null,
                new LlmRetryExecutor(), memory, references, extraction, consolidation,
                mock(MemoryApprovalService.class), approvals);
    }

    // 测试工具与记忆无关，证明通用审批不依赖 isMemoryWriteTool。
    private Tool tool(String name, boolean needsApproval, AtomicInteger counter) {
        return new Tool() {
            // 工具名称用于匹配模型请求。
            public String name() { return name; }
            // 说明不影响审批，审批由后端属性和 Hook 决定。
            public String description() { return "测试工具"; }
            // 可以为任意业务工具启用审批。
            public boolean requiresUserApproval() { return needsApproval; }
            // 只有实际执行才增加计数。
            public ToolExecutionResult execute(String input) { counter.incrementAndGet(); return ToolExecutionResult.success("实际执行结果"); }
        };
    }

    // 模拟可动态变化的权限策略。
    private AgentHook denyHook(AtomicBoolean denied) {
        return new AgentHook() {
            // 真实拒绝不能被批准覆盖。
            public ToolCallHookResult beforeToolExecution(AgentRunContext context, ToolCall call) {
                return denied.get() ? ToolCallHookResult.reject("NO_PERMISSION", "无权执行", false) : ToolCallHookResult.allow();
            }
        };
    }

    // 固定工具响应，便于验证恢复时没有再让模型重新生成调用编号。
    private ToolCallLlmResponse response(String id, String name) { return new ToolCallLlmResponse(List.of(new ToolCall(id, name, "{}"))); }

    // 用户完成当前批次决定，批准阶段本身不执行工具。
    private void approveAll(AgentRunResult run) {
        for (ToolApprovalRequest request : run.getApprovals()) {
            approvals.decide(run.getRunId(), run.getBatchNumber(), request.getToolCallId(), true, null);
        }
    }

    // 模拟带归属条件的仓库查询。
    private AgentApprovalRun owned(String id, Long owner) {
        AgentApprovalRun run = runs.get(id);
        return run != null && run.getUserId().equals(owner) ? run : null;
    }

    // 模拟当前批次查询，不能把上一批的批准结果混入。
    private List<ToolApprovalRequest> batch(String runId, int number) {
        return requests.stream().filter(r -> r.getRunId().equals(runId) && r.getBatchNumber() == number).toList();
    }
}
