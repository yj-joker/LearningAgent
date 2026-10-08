package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.harness.approval.AgentApprovalService;
import com.yjjoker.learningagent.harness.context.*;
import com.yjjoker.learningagent.harness.context.impl.InMemoryOriginalToolResultStoreImpl;
import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.hook.FinalAnswerConsistencyHook;
import com.yjjoker.learningagent.harness.hook.ToolExecutionRecordingHook;
import com.yjjoker.learningagent.harness.review.*;
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
import tools.jackson.databind.json.JsonMapper;

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
    private final AnswerReviewService review = mock(AnswerReviewService.class);
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
        when(history.loadHistory(9L, AgentMode.CHAT)).thenReturn(List.of());
        when(history.loadHistory(9L, AgentMode.FOCUS)).thenReturn(List.of());
        when(memory.loadUserMemoryIndex(7L)).thenReturn(List.of());
        when(memory.loadSessionMemoryIndex(9L)).thenReturn(List.of());
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(PLAN));
        when(client.generate(any())).thenReturn(new TextLlmResponse("事务确保转账同时成功或失败。"));
        when(review.review(any(), any())).thenReturn(new AnswerReviewResult(AnswerReviewResult.Action.PASS, "符合事实", ""));
        when(plans.initialize(eq(9L), any())).thenAnswer(inv -> storedSnapshot(inv.getArgument(1)));
        LlmRetryExecutor retry = new LlmRetryExecutor();
        SessionGoalContext goalContext = new SessionGoalContext();
        harness = new AgentHarnessServiceImpl(client, new ToolRegistry(List.of()),
                List.of(new ToolExecutionRecordingHook(), new FinalAnswerConsistencyHook(review)), history, sessions,
                new ContextManager(5000, 500), new InMemoryOriginalToolResultStoreImpl(), summarizer, retry,
                memory, new MemoryReferenceRegistry(), extraction, mock(MemoryApprovalService.class), approvals,
                plans, new FocusPlanPlanner(client, retry, new ToolRegistry(List.of())), goalContext,
                (runId, userMessage) -> new GoalIntent(
                        false, userMessage != null && userMessage.contains("开始第一步"), false,
                        1.0, "测试替身识别到进度变更"),
                com.yjjoker.learningagent.harness.AgentHarnessTestFactory.emptySkillContext());
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

    // 独立 FOCUS 会话不能被作为 CHAT 使用，拒绝发生在调用模型或加载历史之前。
    @Test
    void rejectsDifferentModeForStandaloneSession() {
        LearningSession session = sessions.findSessionById(9L).orElseThrow();
        session.setMode(AgentMode.FOCUS);
        assertThrows(LearningSessionStatusException.class,
                () -> harness.run(9L, "解释事务", AgentMode.CHAT));
        verify(client, never()).generate(any());
        verify(client, never()).generateWithoutTools(any());
        verify(history, never()).loadHistory(anyLong(), any(AgentMode.class));
    }

    // 没有课程的独立 FOCUS 会话仍能加载自己的计划并生成回答，不调用课程流程。
    @Test
    void runsMatchingStandaloneFocusModeWithoutCourse() {
        LearningSession session = sessions.findSessionById(9L).orElseThrow();
        session.setMode(AgentMode.FOCUS);
        when(plans.load(9L)).thenReturn(storedSnapshot(null));
        AgentRunResult result = harness.run(9L, "解释事务", AgentMode.FOCUS);
        assertEquals(AgentRunStatus.COMPLETED, result.getStatus());
        assertNull(session.getCourseId());
        verify(client).generate(any());
        verify(history).loadHistory(9L, AgentMode.FOCUS);
    }

    // 课程入口的旧 CHAT 调用继续兼容，不因为新增会话模式字段被错误拒绝。
    @Test
    void keepsLegacyCourseChatCompatible() {
        LearningSession session = sessions.findSessionById(9L).orElseThrow();
        session.setCourseId(3L);
        session.setMode(AgentMode.COURSE);
        harness.run(9L, "解释事务", AgentMode.CHAT);
        verify(client).generate(any());
        verifyNoInteractions(plans);
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

    // 精简的是重复规则，不能删掉模型定位目标、理解完成条件所需的真实数据。
    @Test
    void compactPromptPreservesCurrentProgressAndLearningBoundary() {
        when(plans.load(9L)).thenReturn(storedSnapshot(null));
        AgentRunResult result = harness.run(9L, "给我讲解第一步", AgentMode.FOCUS);

        // 核对真实请求与 HTTP 结果使用同一份进度，不从回答文字推断完成状态。
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(client).generate(sent.capture());
        String system = sent.getValue().getFirst().getContent();
        assertTrue(system.contains("当前目标引用：" + result.getProgress().getGoalRef()));
        assertTrue(system.contains("计划版本：" + result.getProgress().getPlanVersion()));
        assertTrue(system.contains(result.getProgress().getSteps().getFirst().getStepRef()));
        assertTrue(system.contains("[PENDING] 解释原子性"));
        assertTrue(system.contains("完成条件：给出转账例子"));
        assertTrue(system.contains("不是已经满足的证明"));
        assertTrue(system.contains("历史助手的说法不是执行凭据"));
        // 动态目标块不重复逐项指挥工具；正式变更仍只能通过原工具和审批完成。
        assertFalse(system.contains("update_task_progress"));
        assertFalse(system.contains("create_memory"));
        verify(plans, never()).updateProgress(any(), any());
        verify(review).review(any(), any());
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
        when(history.loadHistory(9L, AgentMode.FOCUS)).thenReturn(List.of(LlmMessage.user("旧消息".repeat(4000))));
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
        when(review.review(any(), any())).thenReturn(new AnswerReviewResult(
                AnswerReviewResult.Action.CONTINUE, "用户要求的操作未执行", "按用户请求提交必要行动，不能只说完成"));

        AgentRunResult result = harness.run(9L, "开始第一步", AgentMode.FOCUS);

        assertEquals(AgentRunStatus.COMPLETED, result.getStatus());
        assertTrue(result.getAnswer().startsWith(FinalAnswerConsistencyHook.SAFE_ANSWER));
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).generate(sent.capture());
        // 允许补行动时也必须收到完整反馈，但不会因此直接修改进度。
        var feedback = new JsonMapper().readTree(sent.getAllValues().getLast().getLast().getContent());
        assertEquals("RETRY_MODEL", feedback.path("correctionAction").asText());
        assertEquals("用户要求的操作未执行", feedback.path("reviewReason").asText());
        assertEquals("按用户请求提交必要行动，不能只说完成", feedback.path("reviewSuggestion").asText());
        assertEquals("好的，第一步已经完成。", feedback.path("draftToCorrect").asText());
        verify(client, never()).generateWithoutTools(any());
        verify(plans, never()).updateProgress(any(), any());
    }

    // 零工具调用也会审查；改口走无工具调用，错误草稿和临时反馈均不落库。
    @Test
    void rewritesUnsupportedCompletionWithoutTools() {
        when(plans.load(9L)).thenReturn(storedSnapshot(null));
        when(client.generate(any())).thenReturn(new TextLlmResponse("你已掌握，第一步完成。"));
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse("这是第一步讲解。你想练习一下吗？"));
        when(review.review(any(), any())).thenReturn(
                new AnswerReviewResult(AnswerReviewResult.Action.REWRITE, "没有用户反馈", "只讲解，不断言掌握"),
                new AnswerReviewResult(AnswerReviewResult.Action.PASS, "符合事实", ""));
        AgentRunResult result = harness.run(9L, "讲解集合", AgentMode.FOCUS);
        assertEquals("这是第一步讲解。你想练习一下吗？", result.getAnswer());
        verify(client).generate(any());
        ArgumentCaptor<List<LlmMessage>> corrected = ArgumentCaptor.forClass(List.class);
        verify(client).generateWithoutTools(corrected.capture());
        // 原用户消息和数据库进度保留，原因、建议与错误草稿单独放在临时 JSON 中。
        List<LlmMessage> correctionMessages = corrected.getValue();
        var feedback = new JsonMapper().readTree(correctionMessages.getLast().getContent());
        assertEquals("REWRITE_WITHOUT_TOOLS", feedback.path("correctionAction").asText());
        assertEquals("没有用户反馈", feedback.path("reviewReason").asText());
        assertEquals("只讲解，不断言掌握", feedback.path("reviewSuggestion").asText());
        assertEquals("你已掌握，第一步完成。", feedback.path("draftToCorrect").asText());
        assertFalse(feedback.path("draftTruncated").asBoolean());
        assertEquals("讲解集合", correctionMessages.get(1).getContent());
        assertTrue(correctionMessages.getFirst().getContent().contains("[PENDING] 解释原子性"));
        assertTrue(correctionMessages.getFirst().getContent().contains("若与实际审批、工具结果或最新进度冲突，以后者为准"));
        // 复审仍读取原对话，不能把临时反馈误当成用户的另一条要求。
        ArgumentCaptor<AnswerReviewRequest> reviewed = ArgumentCaptor.forClass(AnswerReviewRequest.class);
        verify(review, times(2)).review(any(), reviewed.capture());
        assertEquals("讲解集合", reviewed.getValue().getUserMessage());
        assertEquals(List.of("system", "user"), reviewed.getValue().getDialogue().stream().map(LlmMessage::getRole).toList());
        assertFalse(reviewed.getValue().getDialogue().getFirst().getContent().contains("【回答审查反馈】"));
        ArgumentCaptor<List<LlmMessage>> saved = ArgumentCaptor.forClass(List.class);
        verify(history).appendMessages(eq(9L), eq(AgentMode.FOCUS), saved.capture());
        assertEquals(List.of("讲解集合", result.getAnswer()), saved.getValue().stream().map(LlmMessage::getContent).toList());
        assertEquals(AgentTaskStepStatus.PENDING, result.getProgress().getSteps().getFirst().getStatus());
    }

    // 指代不明确时把原因交给主模型提问，不增加工具能力或推断用户已经授权。
    @Test
    void clarifiesAmbiguousRequestWithCompleteFeedback() {
        when(plans.load(9L)).thenReturn(storedSnapshot(null));
        when(client.generate(any())).thenReturn(new TextLlmResponse("第一步已完成。"));
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse("你是想继续讲解，还是确认通过第一步？"));
        String reason = "用户只说\"继续\"，没有明确确认通过。\n需要澄清意图。";
        when(review.review(any(), any())).thenReturn(
                new AnswerReviewResult(AnswerReviewResult.Action.CLARIFY, reason, "询问继续的含义，不改变进度"),
                new AnswerReviewResult(AnswerReviewResult.Action.PASS, "已澄清", ""));

        AgentRunResult result = harness.run(9L, "继续", AgentMode.FOCUS);

        // 引号和换行由 JSON 序列化，主模型仍能收到完整原因，而不是拼接出的错误格式。
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(client).generateWithoutTools(sent.capture());
        var feedback = new JsonMapper().readTree(sent.getValue().getLast().getContent());
        assertEquals(reason, feedback.path("reviewReason").asText());
        assertEquals("询问继续的含义，不改变进度", feedback.path("reviewSuggestion").asText());
        assertEquals("REWRITE_WITHOUT_TOOLS", feedback.path("correctionAction").asText());
        assertEquals("你是想继续讲解，还是确认通过第一步？", result.getAnswer());
        assertEquals(AgentTaskStepStatus.PENDING, result.getProgress().getSteps().getFirst().getStatus());
        verify(plans, never()).updateProgress(any(), any());
    }

    // 草稿和反馈也占上下文；放不下时返回保护回答，不超预算继续调用模型。
    @Test
    void oversizedCorrectionStopsBeforeSendingToModel() {
        when(plans.load(9L)).thenReturn(storedSnapshot(null));
        when(client.generate(any())).thenReturn(new TextLlmResponse("错误草稿".repeat(2_000)));
        when(review.review(any(), any())).thenReturn(new AnswerReviewResult(
                AnswerReviewResult.Action.REWRITE, "草稿声称步骤已完成", "仅解释现状"));

        AgentRunResult result = harness.run(9L, "讲解第一步", AgentMode.FOCUS);

        assertTrue(result.getAnswer().startsWith(FinalAnswerConsistencyHook.SAFE_ANSWER));
        verify(client).generate(any());
        verify(client, never()).generateWithoutTools(any());
        verify(review).review(any(), any());
        verifyNoInteractions(extraction);
        // 错误草稿和反馈都不保存，只保留用户问题和保护回答。
        ArgumentCaptor<List<LlmMessage>> saved = ArgumentCaptor.forClass(List.class);
        verify(history).appendMessages(eq(9L), eq(AgentMode.FOCUS), saved.capture());
        assertEquals(List.of("讲解第一步", result.getAnswer()), saved.getValue().stream().map(LlmMessage::getContent).toList());
    }

    // 即使无工具接口意外返回工具调用，也不能执行它或把草稿保存成最终答案。
    @Test
    void rejectsUnexpectedToolCallDuringRewrite() {
        when(plans.load(9L)).thenReturn(storedSnapshot(null));
        when(review.review(any(), any())).thenReturn(new AnswerReviewResult(
                AnswerReviewResult.Action.REWRITE, "不实声明", "只修正回答"));
        when(client.generateWithoutTools(any())).thenReturn(new ToolCallLlmResponse(List.of(
                new ToolCall("unexpected", "update_task_progress", "{}"))));
        String protectedAnswer = harness.run(9L, "讲解集合", AgentMode.FOCUS).getAnswer();
        assertTrue(protectedAnswer.startsWith(FinalAnswerConsistencyHook.SAFE_ANSWER));
        verify(plans, never()).updateProgress(any(), any());
        verifyNoInteractions(extraction);
        verify(review).review(any(), any());
    }

    // 审查不可用时不显示原草稿，不进行后置记忆提取。
    @Test
    void reviewFailureDoesNotPublishDraftOrExtractMemory() {
        when(plans.load(9L)).thenReturn(storedSnapshot(null));
        when(review.review(any(), any())).thenReturn(AnswerReviewResult.unavailable());
        String protectedAnswer = harness.run(9L, "讲解集合", AgentMode.FOCUS).getAnswer();
        assertTrue(protectedAnswer.startsWith(FinalAnswerConsistencyHook.SAFE_ANSWER));
        verify(client, never()).generateWithoutTools(any());
        verifyNoInteractions(extraction);
    }

    // 审查期间发生并发修改时，不把旧进度对应的回答写入历史。
    @Test
    void checksDatabaseVersionAgainAfterReview() {
        when(plans.load(9L)).thenReturn(storedSnapshot(null));
        when(review.review(any(), any())).thenAnswer(inv -> {
            doThrow(new IllegalStateException("目标版本已变化")).when(plans).requireUnchanged(any());
            return new AnswerReviewResult(AnswerReviewResult.Action.PASS, "通过", "");
        });
        assertThrows(IllegalStateException.class, () -> harness.run(9L, "讲解集合", AgentMode.FOCUS));
        verify(history, never()).appendMessages(any(), any(AgentMode.class), any());
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
