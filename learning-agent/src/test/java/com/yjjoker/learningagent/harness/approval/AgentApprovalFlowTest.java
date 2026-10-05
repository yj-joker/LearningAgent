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
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.model.*;
import com.yjjoker.learningagent.harness.plan.service.*;
import com.yjjoker.learningagent.harness.review.*;
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
    // 模式测试只控制计划读写结果，审批检查点仍走真实序列化。
    private final SessionGoalService plans = mock(SessionGoalService.class);
    private final SessionGoalContext goalContext = new SessionGoalContext();
    private final FocusPlanPlanner planner = mock(FocusPlanPlanner.class);
    private final MemoryReferenceRegistry references = new MemoryReferenceRegistry();
    private final com.yjjoker.learningagent.notification.ApprovalNotifier notifier =
            mock(com.yjjoker.learningagent.notification.ApprovalNotifier.class);
    private final AgentApprovalService approvals = new AgentApprovalService(repository, sessions, history, notifier);
    // 字符串检查点真的经过序列化和反序列化，不直接复用暂停时的 Java 对象。
    private final Map<String, AgentApprovalRun> runs = new HashMap<>();
    private final List<ToolApprovalRequest> requests = new ArrayList<>();
    private AgentHarnessService harness;
    // 默认空技能保持原用例不变；技能审批测试会换成真实的运行上下文。
    private com.yjjoker.learningagent.harness.skill.service.SkillRunContext skillContext =
            com.yjjoker.learningagent.harness.AgentHarnessTestFactory.emptySkillContext();

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
        List<LlmMessage> savedHistory = List.of(LlmMessage.user("我叫小明"), LlmMessage.assistant("你好，小明"));
        when(history.loadHistory(9L, AgentMode.CHAT)).thenReturn(savedHistory);
        when(history.loadHistory(9L, AgentMode.FOCUS)).thenReturn(savedHistory);
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
        verify(history, never()).appendMessages(any(), any(AgentMode.class), any());
        verifyNoInteractions(extraction, consolidation);
        AgentRunCheckpoint saved = approvals.restore(runs.get(result.getRunId()));
        assertEquals("a", saved.getMessages().getLast().getToolCalls().getFirst().id());
        assertNull(references.currentUserId());
        verify(notifier).changedAfterCommit(7L);
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
        assertEquals("APPROVED", new JsonMapper().readTree(tool.getContent()).path("approvalDecision").asString());
        assertEquals("已完成", harness.resume(paused.getRunId()).getAnswer());
        assertEquals(1, calls.get());
        verify(llm, times(2)).generate(any());
        verify(history, times(1)).loadHistory(9L, AgentMode.CHAT);
        verify(history, times(1)).appendMessages(eq(9L), eq(AgentMode.CHAT), any());
        // 暂停、审批决定、取得恢复权、完成分别通知；重复读完成结果不再通知。
        verify(notifier, times(4)).changedAfterCommit(7L);
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
        LlmMessage denied = messages.getValue().stream().filter(m -> "a".equals(m.getToolCallId())).findFirst().orElseThrow();
        assertEquals("REJECTED", new JsonMapper().readTree(denied.getContent()).path("approvalDecision").asString());
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
        // 第一批的批准事实随轨迹保存，但不是第二批工具的授权。
        AgentRunCheckpoint secondCheckpoint = approvals.restore(runs.get(second.getRunId()));
        assertEquals("APPROVED", secondCheckpoint.getToolExecutions().getFirst().restore().getApprovalDecision());
        assertEquals("b", secondCheckpoint.getPendingCalls().getFirst().id());
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
        doThrow(new IllegalStateException("模拟保存失败")).when(history)
                .appendMessages(any(), any(AgentMode.class), any());
        assertThrows(RuntimeException.class, () -> transactional.complete(paused.getRunId(), 9L,
                AgentMode.CHAT, List.of(LlmMessage.user("执行"), LlmMessage.assistant("完成")), "完成"));
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
                new LlmRetryExecutor(), memory, references, extraction,
                mock(MemoryApprovalService.class), approvals,
                plans, planner, goalContext,
                (runId, userMessage) -> com.yjjoker.learningagent.harness.plan.model.GoalIntent.unknown(), skillContext);
    }

    // 专注暂停后模式随检查点恢复，先执行原工具，再继续模型；不会重复规划。
    @Test
    void focusResumePreservesModePlanAndToolProtocol() {
        AtomicInteger calls = new AtomicInteger();
        build(List.of(tool("change_setting", true, calls)), List.of());
        SessionGoalSnapshot snapshot = focusSnapshot();
        CreateTaskPlanRequest draft = new CreateTaskPlanRequest();
        when(planner.createPlan(anyString(), anyString())).thenReturn(draft);
        when(plans.initialize(eq(9L), same(draft))).thenReturn(snapshot);
        when(llm.generate(any())).thenReturn(response("focus-call", "change_setting"), new TextLlmResponse("设置已调整"));
        AgentRunResult paused = harness.run(9L, "调整设置", AgentMode.FOCUS);
        assertEquals(AgentRunStatus.WAITING_APPROVAL, paused.getStatus());
        assertEquals(0, calls.get());
        AgentRunCheckpoint saved = approvals.restore(runs.get(paused.getRunId()));
        assertEquals(AgentMode.FOCUS, saved.getMode());
        assertTrue(saved.getMessages().getFirst().getContent().contains("按需调整设置"));
        approveAll(paused);
        AgentRunResult done = harness.resume(paused.getRunId());
        assertEquals(paused.getRunId(), done.getRunId());
        assertEquals(AgentRunStatus.COMPLETED, done.getStatus());
        assertEquals(1, calls.get());
        verify(planner).createPlan(anyString(), anyString());
        verify(plans).load(9L);
        assertNotEquals(paused.getRunId(), saved.getGoalSnapshot().getCurrentPlan().getPlanId());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).generate(sent.capture());
        List<LlmMessage> resumed = sent.getAllValues().getLast();
        assertTrue(resumed.getFirst().getContent().contains("当前专注计划"));
        assertEquals(1, resumed.stream().filter(m -> "focus-call".equals(m.getToolCallId())).count());
        assertEquals("{}", runs.get(paused.getRunId()).getCheckpointJson());
    }

    // 审查要求补行动后暂停审批，已用次数经 JSON 保存和恢复，不能再次纠正。
    @Test
    void reviewBudgetSurvivesApprovalAndStillReviewsApprovedOutcome() {
        AnswerReviewService reviewer = mock(AnswerReviewService.class);
        AtomicInteger executions = new AtomicInteger();
        build(List.of(tool("change_setting", true, executions)), List.of(new FinalAnswerConsistencyHook(reviewer)));
        when(plans.load(9L)).thenReturn(focusSnapshot());
        when(reviewer.review(any(), any())).thenReturn(
                new AnswerReviewResult(AnswerReviewResult.Action.CONTINUE, "遗漏行动", "提交用户要求的设置变更"),
                new AnswerReviewResult(AnswerReviewResult.Action.REWRITE, "仍有不实声明", "改正说法"));
        when(llm.generate(any())).thenReturn(new TextLlmResponse("设置已修改"),
                response("review-call", "change_setting"), new TextLlmResponse("设置和所有步骤都已完成"));
        AgentRunResult paused = harness.run(9L, "调整设置", AgentMode.FOCUS);
        assertEquals(AgentRunStatus.WAITING_APPROVAL, paused.getStatus());
        assertEquals(0, executions.get());
        assertEquals(1, approvals.restore(runs.get(paused.getRunId())).getAnswerReviewCorrections());
        // 暂停本身没有再审查；草稿和反馈也不进入检查点。
        verify(reviewer).review(any(), any());
        String checkpointJson = runs.get(paused.getRunId()).getCheckpointJson();
        assertFalse(checkpointJson.contains("draftToCorrect"));
        assertFalse(checkpointJson.contains("reviewReason"));
        assertFalse(checkpointJson.contains("reviewSuggestion"));
        assertFalse(checkpointJson.contains("correctionAction"));
        approveAll(paused);
        AgentRunResult done = harness.resume(paused.getRunId());
        assertTrue(done.getAnswer().startsWith(FinalAnswerConsistencyHook.SAFE_ANSWER));
        assertTrue(done.getAnswer().contains("当前专注目标") || done.getAnswer().contains("工具执行记录"));
        assertEquals(1, executions.get());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm, times(3)).generate(sent.capture());
        // 第二次请求使用临时反馈补行动；批准后的第三次请求只携带真实工具结果。
        var feedback = new JsonMapper().readTree(sent.getAllValues().get(1).getLast().getContent());
        assertEquals("RETRY_MODEL", feedback.path("correctionAction").asText());
        assertEquals("遗漏行动", feedback.path("reviewReason").asText());
        assertEquals("提交用户要求的设置变更", feedback.path("reviewSuggestion").asText());
        String resumedMessages = new JsonMapper().writeValueAsString(sent.getAllValues().getLast());
        assertFalse(resumedMessages.contains("reviewReason"));
        assertFalse(resumedMessages.contains("【回答审查反馈】"));
        verify(llm, never()).generateWithoutTools(any());
        verifyNoInteractions(extraction);
    }

    // 参数失败先修正，审批恢复保留失败分类和预算；批准后只执行一次并正常回答。
    @Test
    void correctedValidationSurvivesApprovalAndPassesFinalReview() {
        AtomicInteger executions = new AtomicInteger();
        AnswerReviewService reviewer = mock(AnswerReviewService.class);
        Tool validatingTool = new Tool() {
            // 测试工具单独声明审批能力，不给生产类增加测试分支。
            @Override public String name() { return "change_setting"; }
            @Override public String description() { return "调整测试设置"; }
            @Override public boolean requiresUserApproval() { return true; }
            // 空对象模拟可修正的参数错误，预检不执行写操作。
            @Override public ToolExecutionResult validateApprovalInput(String input) {
                return input.equals("{}")
                        ? ToolExecutionResult.failure("INVALID_INPUT", "请提供 value", true)
                        : ToolExecutionResult.success("参数有效");
            }
            // 计数证明只在批准并恢复之后真正执行。
            @Override public ToolExecutionResult execute(String input) {
                executions.incrementAndGet();
                return ToolExecutionResult.success("设置已修改");
            }
        };
        build(List.of(validatingTool), List.of(new FinalAnswerConsistencyHook(reviewer)));
        when(plans.load(9L)).thenReturn(focusSnapshot());
        when(llm.generate(any())).thenReturn(response("bad-input", "change_setting"),
                new TextLlmResponse("无法修改"),
                new ToolCallLlmResponse(List.of(new ToolCall("corrected", "change_setting", "{\"value\":1}"))),
                new TextLlmResponse("设置已修改"));
        when(reviewer.review(any(), any())).thenReturn(
                new AnswerReviewResult(AnswerReviewResult.Action.CONTINUE, "参数可修正", "补上 value 后申请"),
                new AnswerReviewResult(AnswerReviewResult.Action.PASS, "批准后已经执行", ""));

        AgentRunResult paused = harness.run(9L, "调整设置", AgentMode.FOCUS);
        assertEquals(AgentRunStatus.WAITING_APPROVAL, paused.getStatus());
        assertEquals(0, executions.get());
        AgentRunCheckpoint checkpoint = approvals.restore(runs.get(paused.getRunId()));
        assertEquals(ToolExecutionRecord.Status.VALIDATION_FAILED,
                checkpoint.getToolExecutions().getFirst().restore().getStatus());
        assertEquals(1, checkpoint.getAnswerReviewCorrections());
        assertEquals(1, requests.size());
        approveAll(paused);
        AgentRunResult completed = harness.resume(paused.getRunId());
        assertEquals("设置已修改", completed.getAnswer());
        assertEquals(1, executions.get());
        assertEquals("{}", runs.get(paused.getRunId()).getCheckpointJson());
        verify(llm, times(4)).generate(any());
        verify(llm, never()).generateWithoutTools(any());
        verify(reviewer, times(2)).review(any(), any());
        ArgumentCaptor<AgentRunContext> reviewed = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(reviewer, times(2)).review(reviewed.capture(), any());
        // 恢复后的审查看到两次独立尝试，以及后一次已经批准和成功执行的事实。
        var outcomes = reviewed.getValue().getToolExecutions();
        assertEquals(ToolExecutionRecord.Status.VALIDATION_FAILED, outcomes.getFirst().getStatus());
        assertNull(outcomes.getFirst().getApprovalDecision());
        assertEquals(ToolExecutionRecord.Status.SUCCEEDED, outcomes.getLast().getStatus());
        assertEquals("APPROVED", outcomes.getLast().getApprovalDecision());
    }

    // 拒绝后模型的虚假成功声明只允许改口，审查错误建议补执行也不能产生第二次申请。
    @Test
    void rejectedApprovalCanOnlyRewriteAndNeverReapply() {
        AnswerReviewService reviewer = mock(AnswerReviewService.class);
        AtomicInteger executions = new AtomicInteger();
        build(List.of(tool("change_setting", true, executions)), List.of(new FinalAnswerConsistencyHook(reviewer)));
        when(plans.load(9L)).thenReturn(focusSnapshot());
        when(llm.generate(any())).thenReturn(response("rejected-review-call", "change_setting"),
                new TextLlmResponse("设置已完成"));
        when(reviewer.review(any(), any())).thenReturn(
                new AnswerReviewResult(AnswerReviewResult.Action.CONTINUE, "误判未执行", "再申请一次"),
                new AnswerReviewResult(AnswerReviewResult.Action.PASS, "如实说明拒绝", ""));
        when(llm.generateWithoutTools(any())).thenReturn(new TextLlmResponse("你拒绝了变更，设置未修改。"));
        AgentRunResult paused = harness.run(9L, "调整设置", AgentMode.FOCUS);
        verifyNoInteractions(reviewer);
        approvals.decide(paused.getRunId(), paused.getBatchNumber(), "rejected-review-call", false, "不修改");
        AgentRunResult done = harness.resume(paused.getRunId());
        assertEquals("你拒绝了变更，设置未修改。", done.getAnswer());
        assertEquals(0, executions.get());
        assertEquals(1, requests.size());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm).generateWithoutTools(sent.capture());
        // 后端同时替换错误原因和错误建议；原审批拒绝仍作为事实保留在上下文中。
        var feedback = new JsonMapper().readTree(sent.getValue().getLast().getContent());
        assertEquals("REWRITE_WITHOUT_TOOLS", feedback.path("correctionAction").asText());
        assertEquals("本轮存在真实拒绝、不可重试失败或不确定结果，后端不允许继续工具调用。",
                feedback.path("reviewReason").asText());
        assertEquals("只如实解释现状或询问用户，不声称成功、不重复申请。", feedback.path("reviewSuggestion").asText());
        assertTrue(sent.getValue().stream().anyMatch(message -> "tool".equals(message.getRole())
                && "rejected-review-call".equals(message.getToolCallId())
                && message.getContent().contains("APPROVAL_REJECTED")));
        // 最终历史只保存真实对话及工具结果，不把审查反馈带入下一轮。
        ArgumentCaptor<List<LlmMessage>> saved = ArgumentCaptor.forClass(List.class);
        verify(history).appendMessages(eq(9L), eq(AgentMode.FOCUS), saved.capture());
        assertFalse(new JsonMapper().writeValueAsString(saved.getValue()).contains("reviewSuggestion"));
    }

    // 恢复时计划丢失必须停止，不能先执行审批工具再发现前置条件不满足。
    @Test
    void missingFocusPlanStopsApprovedTool() {
        AtomicInteger calls = new AtomicInteger();
        build(List.of(tool("change_setting", true, calls)), List.of());
        when(planner.createPlan(anyString(), anyString())).thenReturn(new CreateTaskPlanRequest());
        when(plans.initialize(eq(9L), any())).thenReturn(focusSnapshot());
        when(llm.generate(any())).thenReturn(response("focus-call", "change_setting"));
        AgentRunResult paused = harness.run(9L, "调整设置", AgentMode.FOCUS);
        approveAll(paused);
        doThrow(new IllegalStateException("计划不可用")).when(plans).requireUnchanged(any());
        assertThrows(IllegalStateException.class, () -> harness.resume(paused.getRunId()));
        assertEquals(0, calls.get());
        verify(llm).generate(any());
        assertEquals(AgentRunStatus.FAILED, runs.get(paused.getRunId()).getStatus());
    }

    // 真实工具、Hook、序列化检查点和 Harness 一起运行，验证切换后立即替换系统目标。
    @Test
    void createsApprovedGoalThenReturnsToOriginalWithoutReplanning() {
        SessionGoalSnapshot original = focusSnapshot();
        SessionGoalSnapshot other = focusSnapshot();
        other.getCurrentPlan().setGoal("学习数据库锁");
        other.getCurrentPlan().setGoalNumber(2);
        other.getCurrentPlan().getSteps().getFirst().setDescription("分析行锁");
        other.getState().setVersion(3);
        other.getState().setNextGoalNumber(3);
        other.setGoals(List.of(original.getCurrentPlan(), other.getCurrentPlan()));
        var service = new SessionGoalToolService(goalContext, plans);
        build(List.of(new com.yjjoker.learningagent.harness.tool.impl.CreateSessionGoalTool(service),
                new com.yjjoker.learningagent.harness.tool.impl.SwitchSessionGoalTool(service)), List.of());
        when(plans.load(9L)).thenReturn(original);
        when(plans.create(any(), any())).thenReturn(other);
        String input = "{\"goal\":\"学习数据库锁\",\"steps\":[{\"description\":\"分析行锁\",\"completionCriteria\":\"给出例子\"}]}";
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(
                new ToolCall("new-goal", "create_session_goal", input))), new TextLlmResponse("开始解释行锁"));
        AgentRunResult paused = harness.run(9L, "换个目标，学习数据库锁", AgentMode.FOCUS);
        assertEquals(AgentRunStatus.WAITING_APPROVAL, paused.getStatus());
        verify(plans, never()).create(any(), any());
        verify(llm).generate(any());
        approveAll(paused);
        assertEquals(AgentRunStatus.COMPLETED, harness.resume(paused.getRunId()).getStatus());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).generate(sent.capture());
        String updated = sent.getAllValues().getLast().getFirst().getContent();
        assertTrue(updated.contains("当前目标引用：goal-2"));
        assertTrue(updated.contains("goal-1 [搁置]"));
        assertTrue(updated.contains("分析行锁"));
        assertFalse(updated.contains("按需调整设置"));
        assertThrows(SecurityException.class, goalContext::require);
        // 重复恢复只返回已保存答案，不再次创建目标。
        harness.resume(paused.getRunId());
        verify(plans).create(any(), any());

        // 下一次用户消息加载同一会话目标；批准后回到原编号、原步骤，不再次规划。
        SessionGoalSnapshot resumed = focusSnapshot();
        resumed.setCurrentPlan(original.getCurrentPlan());
        resumed.getState().setActivePlanId(original.getCurrentPlan().getPlanId());
        resumed.getState().setVersion(4);
        resumed.setGoals(other.getGoals());
        when(plans.load(9L)).thenReturn(other);
        when(plans.switchTo(any(), eq("goal-1"))).thenReturn(resumed);
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(
                new ToolCall("old-goal", "switch_session_goal", "{\"goalRef\":\"goal-1\"}"))),
                new TextLlmResponse("继续原目标"));
        AgentRunResult back = harness.run(9L, "回到原来的目标", AgentMode.FOCUS);
        assertTrue(back.getApprovals().getFirst().getReason().contains("学习数据库锁"));
        assertTrue(back.getApprovals().getFirst().getReason().contains("调整设置"));
        approveAll(back);
        harness.resume(back.getRunId());
        verify(llm, times(4)).generate(sent.capture());
        String backPrompt = sent.getAllValues().getLast().getFirst().getContent();
        assertTrue(backPrompt.contains("当前目标引用：goal-1"));
        assertTrue(backPrompt.contains("按需调整设置"));
        verifyNoInteractions(planner);
    }

    // 用户拒绝目标变更后，旧目标仍在上下文中，不执行写入。
    @Test
    void rejectedGoalChangeKeepsCurrentPlan() {
        when(plans.load(9L)).thenReturn(focusSnapshot());
        var service = new SessionGoalToolService(goalContext, plans);
        build(List.of(new com.yjjoker.learningagent.harness.tool.impl.CreateSessionGoalTool(service)), List.of());
        String input = "{\"goal\":\"新目标\",\"steps\":[{\"description\":\"解释\",\"completionCriteria\":\"给出例子\"}]}";
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(new ToolCall("new-goal", "create_session_goal", input))),
                new TextLlmResponse("保留原目标"));
        AgentRunResult paused = harness.run(9L, "换个目标", AgentMode.FOCUS);
        approvals.decide(paused.getRunId(), 1, "new-goal", false, "先不切换");
        harness.resume(paused.getRunId());
        verify(plans, never()).create(any(), any());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).generate(sent.capture());
        assertTrue(sent.getAllValues().getLast().getFirst().getContent().contains("当前目标引用：goal-1"));
    }

    // 改变方向的调用与其他工具混用时，整批不执行，也不创建审批申请。
    @Test
    void rejectsMixedBatchBeforeApprovalOrBusinessSideEffects() {
        when(plans.load(9L)).thenReturn(focusSnapshot());
        AtomicInteger businessCalls = new AtomicInteger();
        var service = new SessionGoalToolService(goalContext, plans);
        build(List.of(new com.yjjoker.learningagent.harness.tool.impl.SwitchSessionGoalTool(service),
                tool("business", false, businessCalls)), List.of());
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(
                new ToolCall("switch", "switch_session_goal", "{\"goalRef\":\"goal-2\"}"),
                new ToolCall("business-call", "business", "{}"))), new TextLlmResponse("请确认目标"));
        assertEquals(AgentRunStatus.COMPLETED, harness.run(9L, "切换目标", AgentMode.FOCUS).getStatus());
        assertEquals(0, businessCalls.get());
        assertTrue(requests.isEmpty());
        verify(plans, never()).switchTo(any(), any());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).generate(sent.capture());
        assertEquals(2, sent.getAllValues().getLast().stream().filter(m -> "tool".equals(m.getRole())
                && m.getContent().contains("EXCLUSIVE_TOOL_BATCH")).count());
    }

    // 进度工具复用真实审批暂停和检查点；批准、恢复成功后才刷新模型状态。
    @Test
    void confirmsProgressThenRefreshesPromptAndDoesNotRepeatExecution() {
        SessionGoalSnapshot before = progressSnapshot();
        SessionGoalSnapshot after = new JsonMapper().readValue(new JsonMapper().writeValueAsString(before), SessionGoalSnapshot.class);
        after.getCurrentPlan().setVersion(2);
        after.getCurrentPlan().getSteps().getFirst().setStatus(AgentTaskStepStatus.COMPLETED);
        after.getCurrentPlan().getSteps().getFirst().setResultSummary("用户确认继续，未验证掌握");
        when(plans.load(9L)).thenReturn(before);
        when(plans.updateProgress(any(), any())).thenReturn(after);
        var tool = new com.yjjoker.learningagent.harness.tool.impl.UpdateTaskProgressTool(new TaskProgressToolService(goalContext, plans));
        build(List.of(tool), List.of());
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(new ToolCall("progress", tool.name(), progressInput()))),
                new TextLlmResponse("已按你的确认更新步骤；尚未验证掌握程度。"));
        AgentRunResult paused = harness.run(9L, "本步骤通过，继续下一步", AgentMode.FOCUS);
        assertEquals(AgentRunStatus.WAITING_APPROVAL, paused.getStatus());
        verify(plans, never()).updateProgress(any(), any());
        verify(llm, times(1)).generate(any());
        approveAll(paused);
        verify(plans, never()).updateProgress(any(), any());
        AgentRunResult finished = harness.resume(paused.getRunId());
        assertEquals(AgentRunStatus.COMPLETED, finished.getStatus());
        verify(plans).updateProgress(any(), any());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).generate(sent.capture());
        String prompt = sent.getAllValues().getLast().getFirst().getContent();
        assertTrue(prompt.contains("[COMPLETED]"));
        assertTrue(prompt.contains("goal-1-v2-step-1"));
        assertTrue(prompt.contains("未验证掌握"));
        assertFalse(prompt.contains("goal-1-v1-step-1"));
        assertTrue(sent.getAllValues().getLast().stream()
                .filter(m -> "progress".equals(m.getToolCallId())).allMatch(m -> !m.isContextReplayable()));
        assertEquals(finished.getAnswer(), harness.resume(paused.getRunId()).getAnswer());
        verify(plans, times(1)).updateProgress(any(), any());
        verify(llm, times(2)).generate(any());
        assertEquals("{}", runs.get(paused.getRunId()).getCheckpointJson());
        assertThrows(SecurityException.class, goalContext::require);
    }

    // 用户拒绝后保留原进度，并把拒绝结果交回模型，而不是伪造成功。
    @Test
    void rejectedProgressKeepsOriginalStepState() {
        when(plans.load(9L)).thenReturn(progressSnapshot());
        var tool = new com.yjjoker.learningagent.harness.tool.impl.UpdateTaskProgressTool(new TaskProgressToolService(goalContext, plans));
        build(List.of(tool), List.of());
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(new ToolCall("progress", tool.name(), progressInput()))),
                new TextLlmResponse("没有更新进度，继续当前步骤。"));
        AgentRunResult paused = harness.run(9L, "本步骤通过，继续下一步", AgentMode.FOCUS);
        approvals.decide(paused.getRunId(), 1, "progress", false, "还没有完成");
        harness.resume(paused.getRunId());
        verify(plans, never()).updateProgress(any(), any());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).generate(sent.capture());
        assertTrue(sent.getAllValues().getLast().getFirst().getContent().contains("[IN_PROGRESS]"));
        assertTrue(sent.getAllValues().getLast().stream().filter(m -> "tool".equals(m.getRole()))
                .anyMatch(m -> m.getContent().contains("APPROVAL_REJECTED")));
    }

    // 审批等待期间版本已变化，恢复必须在执行进度工具之前停止。
    @Test
    void changedPlanStopsApprovedProgressBeforeExecution() {
        when(plans.load(9L)).thenReturn(progressSnapshot());
        var tool = new com.yjjoker.learningagent.harness.tool.impl.UpdateTaskProgressTool(new TaskProgressToolService(goalContext, plans));
        build(List.of(tool), List.of());
        when(llm.generate(any())).thenReturn(new ToolCallLlmResponse(List.of(new ToolCall("progress", tool.name(), progressInput()))));
        AgentRunResult paused = harness.run(9L, "本步骤通过，继续下一步", AgentMode.FOCUS);
        approveAll(paused);
        doThrow(new com.yjjoker.learningagent.exception.ClientDataErrorException("计划版本已变化"))
                .when(plans).requireUnchanged(any());
        assertThrows(com.yjjoker.learningagent.exception.ClientDataErrorException.class, () -> harness.resume(paused.getRunId()));
        verify(plans, never()).updateProgress(any(), any());
        verify(llm, times(1)).generate(any());
    }

    // 用户原话随检查点恢复；对话依据不能被助手的结论代替。
    private String progressInput() {
        return """
                {"updates":[{"stepRef":"goal-1-v1-step-1","status":"COMPLETED","reason":"用户要求继续学习",\
                "completionBasis":"USER_CONFIRMED","userEvidence":"本步骤通过，继续下一步"}]}
                """;
    }

    // 补齐工具解析需要的固定身份，保持既有其他审批测试的样例不变。
    private SessionGoalSnapshot progressSnapshot() {
        SessionGoalSnapshot snapshot = focusSnapshot();
        AgentTaskStep step = snapshot.getCurrentPlan().getSteps().getFirst();
        step.setStepId(UUID.randomUUID().toString());
        step.setPlanId(snapshot.getCurrentPlan().getPlanId());
        step.setStatus(AgentTaskStepStatus.IN_PROGRESS);
        return snapshot;
    }

    // 构造一个短计划，不假设步骤已经完成。
    private SessionGoalSnapshot focusSnapshot() {
        AgentTaskStep step = new AgentTaskStep();
        step.setPosition(1);
        step.setDescription("按需调整设置");
        step.setCompletionCriteria("得到真实工具结果");
        step.setStatus(AgentTaskStepStatus.PENDING);
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setPlanId(UUID.randomUUID().toString());
        plan.setGoalNumber(1);
        plan.setUserId(7L);
        plan.setSessionId(9L);
        plan.setGoal("调整设置");
        plan.setVersion(1);
        plan.setSteps(List.of(step));
        SessionFocusState state = new SessionFocusState();
        state.setUserId(7L);
        state.setSessionId(9L);
        state.setActivePlanId(plan.getPlanId());
        state.setVersion(2);
        state.setNextGoalNumber(2);
        SessionGoalSnapshot snapshot = new SessionGoalSnapshot();
        snapshot.setState(state);
        snapshot.setCurrentPlan(plan);
        snapshot.setGoals(List.of(plan));
        return snapshot;
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
