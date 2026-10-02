package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.approval.AgentApprovalService;
import com.yjjoker.learningagent.harness.context.*;
import com.yjjoker.learningagent.harness.context.impl.InMemoryOriginalToolResultStoreImpl;
import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.hook.ToolExecutionRecordingHook;
import com.yjjoker.learningagent.harness.llm.*;
import com.yjjoker.learningagent.harness.llm.model.*;
import com.yjjoker.learningagent.harness.memory.service.*;
import com.yjjoker.learningagent.harness.model.*;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.model.*;
import com.yjjoker.learningagent.harness.plan.service.*;
import com.yjjoker.learningagent.harness.service.AgentHarnessServiceImpl;
import com.yjjoker.learningagent.harness.tool.*;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.AgentRunResult;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 走真实 Harness 编排；数据库和模型使用替身，准确检查调用顺序。
class AgentHarnessModeTest {
    private static final String PLAN = """
            {"goal":"理解事务","steps":[{"description":"解释原子性","completionCriteria":"给出转账例子"}]}
            """;
    private final LlmClient client = mock(LlmClient.class);
    private final SessionGoalService plans = mock(SessionGoalService.class);
    private final AgentApprovalService approvals = mock(AgentApprovalService.class);
    private final ConversationMemoryService history = mock(ConversationMemoryService.class);
    private final StructuredMemoryService memory = mock(StructuredMemoryService.class);
    private final MemoryExtractionService extraction = mock(MemoryExtractionService.class);
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final ContextSummarizer summarizer = mock(ContextSummarizer.class);
    private AgentHarnessServiceImpl harness;

    // 准备当前用户的空会话，默认规划和主回答均成功。
    @BeforeEach
    void setup() {
        BaseContext.setCurrentId(7L);
        LearningSession session = new LearningSession();
        session.setId(9L);
        session.setUserId(7L);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(9L)).thenReturn(Optional.of(session));
        when(history.loadHistory(9L)).thenReturn(List.of());
        when(memory.loadUserMemoryIndex(7L)).thenReturn(List.of());
        when(memory.loadSessionMemoryIndex(9L)).thenReturn(List.of());
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(PLAN));
        when(client.generate(any())).thenReturn(new TextLlmResponse("事务确保转账同时成功或失败。"));
        when(plans.initialize(eq(9L), any())).thenAnswer(inv -> storedSnapshot(inv.getArgument(1)));
        LlmRetryExecutor retry = new LlmRetryExecutor();
        harness = new AgentHarnessServiceImpl(client, new ToolRegistry(List.of()),
                List.of(new ToolExecutionRecordingHook()), history, sessions,
                new ContextManager(5000, 500), new InMemoryOriginalToolResultStoreImpl(), summarizer, retry,
                memory, new MemoryReferenceRegistry(), extraction, mock(MemoryApprovalService.class), approvals,
                plans, new FocusPlanPlanner(client, retry, new ToolRegistry(List.of())), new SessionGoalContext());
    }

    // 不让登录身份残留到其他测试。
    @AfterEach
    void clear() {
        BaseContext.removeCurrentId();
    }

    // 旧方法和显式 CHAT 都不查询计划、不增加规划调用。
    @Test
    void keepsChatCompatible() {
        harness.run(9L, "解释事务");
        harness.run(9L, "解释事务", AgentMode.CHAT);
        verifyNoInteractions(plans);
        verify(client, never()).generateWithoutTools(any());
        verify(client, times(2)).generate(any());
    }

    // 没有结构化记忆也必须带上计划，规划规则不会混入主提示词。
    @Test
    void plansAndPersistsBeforeExecutingEvenWithoutMemory() {
        var result = harness.run(9L, "解释事务", AgentMode.FOCUS);
        assertEquals(AgentRunStatus.COMPLETED, result.getStatus());
        var order = inOrder(approvals, plans, client);
        order.verify(approvals).requireSessionAvailable(9L);
        order.verify(plans).load(9L);
        order.verify(client).generateWithoutTools(any());
        order.verify(plans).initialize(eq(9L), any());
        order.verify(client).generate(any());
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(client).generate(sent.capture());
        String system = sent.getValue().getFirst().getContent();
        assertTrue(system.contains("当前专注计划"));
        assertTrue(system.contains("给出转账例子"));
        assertFalse(system.contains("你是 LearningAgent 的专注模式规划器"));
        // 本阶段只建立与执行计划，不伪造任何完成进度。
        verify(plans, never()).switchTo(any(), anyString());
    }

    // 已有计划直接复用，不调用模型创建第二份。
    @Test
    void reusesExistingPlan() {
        when(plans.load(9L)).thenReturn(storedSnapshot(null));
        harness.run(9L, "解释事务", AgentMode.FOCUS);
        verify(client, never()).generateWithoutTools(any());
        verify(plans, never()).initialize(anyLong(), any());
    }

    // 规划格式两次失败时，不执行主模型和数据库写入。
    @Test
    void invalidPlanStopsBeforeMainLoop() {
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse("错误"));
        assertThrows(HarnessException.class, () -> harness.run(9L, "解释事务", AgentMode.FOCUS));
        verify(client, times(2)).generateWithoutTools(any());
        verify(client, never()).generate(any());
        verify(plans, never()).initialize(anyLong(), any());
        verifyNoInteractions(extraction);
    }

    // 保存失败不能交给规划器重试，更不能在未保存时执行任务。
    @Test
    void persistenceFailureDoesNotReplanOrExecute() {
        when(plans.initialize(eq(9L), any())).thenThrow(new IllegalStateException("模拟数据库故障"));
        assertThrows(IllegalStateException.class, () -> harness.run(9L, "解释事务", AgentMode.FOCUS));
        verify(client).generateWithoutTools(any());
        verify(client, never()).generate(any());
    }

    // 待审批会话必须先被拒绝，不能白白花钱再生成一份新计划。
    @Test
    void checksWaitingApprovalBeforePlanning() {
        doThrow(new ClientDataErrorException("请先完成审批")).when(approvals).requireSessionAvailable(9L);
        assertThrows(ClientDataErrorException.class, () -> harness.run(9L, "解释事务", AgentMode.FOCUS));
        verifyNoInteractions(plans, client);
    }

    // 摘要只能替换旧历史，位于系统消息中的专注计划仍完整保留。
    @Test
    void preservesPlanThroughHistorySummary() {
        when(history.loadHistory(9L)).thenReturn(List.of(LlmMessage.user("旧消息".repeat(4000))));
        when(summarizer.summarize(any())).thenReturn("用户之前学习过 Java。");
        harness.run(9L, "解释事务", AgentMode.FOCUS);
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(client).generate(sent.capture());
        assertTrue(sent.getValue().getFirst().getContent().contains("给出转账例子"));
        assertTrue(sent.getValue().stream().anyMatch(LlmMessage::isSummary));
        verify(client).generateWithoutTools(any());
    }

    // 明确要求推进但模型只说“完成”时，第一次回答不直接落库，第二次仍失败则返回后端保护结果。
    @Test
    void guardsExplicitProgressMutationThatHasNoSuccessfulTool() {
        when(plans.load(9L)).thenReturn(storedSnapshot(null));
        when(client.generate(any())).thenReturn(
                new TextLlmResponse("好的，第一步已经完成。"),
                new TextLlmResponse("我确认已经完成。"));

        AgentRunResult result = harness.run(9L, "开始第一步", AgentMode.FOCUS);

        assertEquals(AgentRunStatus.COMPLETED, result.getStatus());
        assertEquals("本次请求要求修改步骤进度，但模型没有成功提交 update_task_progress，数据库状态未改变。请重新确认后再继续。",
                result.getAnswer());
        verify(client, times(2)).generate(any());
        verify(plans, never()).updateProgress(any(), any());
    }

    // 生成与 runId 独立的持久目标，后续多次请求可以复用它。
    private SessionGoalSnapshot storedSnapshot(CreateTaskPlanRequest request) {
        String planId = UUID.randomUUID().toString();
        AgentTaskStep step = new AgentTaskStep();
        step.setStepId(UUID.randomUUID().toString());
        step.setPlanId(planId);
        step.setPosition(1);
        step.setStatus(AgentTaskStepStatus.PENDING);
        step.setDescription(request == null ? "解释原子性" : request.getSteps().getFirst().getDescription());
        step.setCompletionCriteria("给出转账例子");
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setPlanId(planId);
        plan.setGoalNumber(1);
        plan.setUserId(7L);
        plan.setSessionId(9L);
        plan.setGoal("理解事务");
        plan.setVersion(1);
        plan.setSteps(List.of(step));
        SessionFocusState state = new SessionFocusState();
        state.setUserId(7L);
        state.setSessionId(9L);
        state.setActivePlanId(planId);
        state.setVersion(2);
        state.setNextGoalNumber(2);
        SessionGoalSnapshot snapshot = new SessionGoalSnapshot();
        snapshot.setState(state);
        snapshot.setCurrentPlan(plan);
        snapshot.setGoals(List.of(plan));
        return snapshot;
    }
}
