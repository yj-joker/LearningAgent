package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.plan.dto.UpdateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.model.*;
import com.yjjoker.learningagent.harness.plan.service.*;
import com.yjjoker.learningagent.harness.tool.impl.UpdateTaskProgressTool;
import com.yjjoker.learningagent.repository.AgentTaskPlanRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 使用真实参数解析和状态规则；数据库服务替换为测试对象，不调用真实模型。
class TaskProgressToolServiceTest {
    private final SessionGoalContext context = new SessionGoalContext();
    private final SessionGoalService goals = mock(SessionGoalService.class);
    private final TaskProgressToolService service = new TaskProgressToolService(context, goals);
    private final AgentTaskPlanRepository repository = mock(AgentTaskPlanRepository.class);
    private final AgentTaskPlanService validation = new AgentTaskPlanService(repository, mock(LearningSessionRepository.class));
    private final JsonMapper json = new JsonMapper();
    private SessionGoalSnapshot original;

    // 一步正在执行、一步未开始；原话只放当前用户说过的内容。
    @BeforeEach
    void setup() {
        BaseContext.setCurrentId(7L);
        original = snapshot();
        context.bind(original);
        context.bindDialogue(List.of(LlmMessage.user("我能说明核心线程与最大线程的区别。"),
                LlmMessage.assistant("助手说：你已经完全掌握。")));
        doAnswer(inv -> {
            validation.validateUpdate(((SessionGoalSnapshot) inv.getArgument(0)).getCurrentPlan(), inv.getArgument(1));
            return null;
        }).when(goals).validateProgress(any(), any());
        when(goals.updateProgress(any(), any())).thenAnswer(inv -> committed(inv.getArgument(1)));
    }

    // 每个测试结束都清空上下文，防止线程复用串入旧证据。
    @AfterEach
    void clear() { context.clear(); BaseContext.removeCurrentId(); }

    // 同批结束当前步骤并启动下一步，审批之前不变更任何对象或数据库。
    @Test
    void validatesBatchWithoutWriting() {
        String input = input(change(1, "COMPLETED", "USER_CONFIRMED", ""), change(2, "IN_PROGRESS", "NOT_APPLICABLE", ""));
        assertTrue(service.update(input, false).isSuccess());
        verify(goals, never()).updateProgress(any(), any());
        verifyNoInteractions(repository);
        assertEquals(AgentTaskStepStatus.IN_PROGRESS, original.getCurrentPlan().getSteps().getFirst().getStatus());
        assertEquals(1, original.getCurrentPlan().getVersion());
    }

    // 用户确认不要求答题，但结果必须保留未验证掌握的说明。
    @Test
    void commitsUserConfirmedProgressAndRefreshesReferences() {
        var result = service.update(input(change(1, "COMPLETED", "USER_CONFIRMED", ""),
                change(2, "IN_PROGRESS", "NOT_APPLICABLE", "")), true);
        assertTrue(result.isSuccess());
        var saved = context.require().getCurrentPlan();
        assertEquals(2, saved.getVersion());
        assertEquals(AgentTaskStepStatus.COMPLETED, saved.getSteps().getFirst().getStatus());
        assertEquals(AgentTaskStepStatus.IN_PROGRESS, saved.getSteps().get(1).getStatus());
        assertTrue(saved.getSteps().getFirst().getResultSummary().contains("用户选择继续，未验证掌握"));
        assertEquals("能说明核心线程与最大线程的区别", saved.getSteps().getFirst().getCompletionCriteria());
        assertTrue(result.getContent().contains("goal-1-v2-step-1"));
        assertTrue(result.getContent().contains("\"masteryVerified\":false"));
        assertEquals(AgentTaskStepStatus.IN_PROGRESS, original.getCurrentPlan().getSteps().getFirst().getStatus());
    }

    // 对话依据保存真实用户原话，不额外复制完整历史。
    @Test
    void retainsDialogueReasonAndEvidence() {
        String evidence = "我能说明核心线程与最大线程的区别。";
        assertTrue(service.update(input(change(1, "COMPLETED", "DIALOGUE_EVIDENCE", evidence)), true).isSuccess());
        String summary = context.require().getCurrentPlan().getSteps().getFirst().getResultSummary();
        assertTrue(summary.contains(evidence));
        assertTrue(summary.contains("对话依据，由用户确认"));
        assertTrue(summary.contains("尚未独立验证"));
    }

    // 不能把助手自己的结论、伪造原话或空引用当成用户证据。
    @ParameterizedTest
    @ValueSource(strings = {"", "用户完成了三道题", "助手说：你已经完全掌握。"})
    void rejectsMissingOrFabricatedEvidence(String evidence) {
        assertFalse(service.update(input(change(1, "COMPLETED", "DIALOGUE_EVIDENCE", evidence)), true).isSuccess());
        verify(goals, never()).updateProgress(any(), any());
    }

    // 原状态规则仍有效：未开始不能直接完成，两个步骤不能同时执行。
    @Test
    void keepsExistingTransitionRules() {
        assertFalse(service.update(input(change(2, "COMPLETED", "USER_CONFIRMED", "")), false).isSuccess());
        assertFalse(service.update(input(change(2, "IN_PROGRESS", "NOT_APPLICABLE", "")), false).isSuccess());
        verify(goals, never()).updateProgress(any(), any());
    }

    // 阻塞后可以恢复；取消和阻塞也保存原因，而不是伪装为掌握判定。
    @Test
    void supportsBlockedResumedAndCanceledProgress() {
        assertTrue(service.update(input(change(1, "BLOCKED", "NOT_APPLICABLE", "")), true).isSuccess());
        assertTrue(service.update(input(change(1, "IN_PROGRESS", "NOT_APPLICABLE", "")), true).isSuccess());
        assertTrue(service.update(input(change(1, "CANCELED", "NOT_APPLICABLE", "")), true).isSuccess());
        assertEquals(AgentTaskStepStatus.CANCELED, context.require().getCurrentPlan().getSteps().getFirst().getStatus());
    }

    // 不允许直接改写完成条件、数据库 ID、版本，也不接受尾随 JSON。
    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"updates\":[]}", "{\"updates\":[],\"sessionId\":9}",
            "{\"updates\":[],\"updates\":[]}", "{} {}", "{\"updates\":[{\"stepId\":\"x\"}]}"})
    void rejectsMalformedRequests(String input) {
        assertFalse(service.update(input, true).isSuccess());
        verify(goals, never()).updateProgress(any(), any());
    }

    // 相同位置在其他目标或其他版本不是同一步，不能继续解释旧引用。
    @ParameterizedTest
    @ValueSource(strings = {"goal-2-v1-step-1", "goal-1-v2-step-1", "step-1", "goal-1-v1-step-3"})
    void rejectsForeignOrStaleReferences(String ref) {
        var change = change(1, "COMPLETED", "USER_CONFIRMED", "");
        change.put("stepRef", ref);
        assertFalse(service.update(input(change), true).isSuccess());
        verify(goals, never()).updateProgress(any(), any());
    }

    // 依据类型必须匹配目标状态，不能用空类型把完成条件绕过去。
    @Test
    void rejectsDuplicateChangesAndWrongBasis() {
        var change = change(1, "COMPLETED", "USER_CONFIRMED", "");
        assertFalse(service.update(input(change, change), false).isSuccess());
        assertFalse(service.update(input(change(1, "COMPLETED", "NOT_APPLICABLE", "")), false).isSuccess());
        assertFalse(service.update(input(change(1, "BLOCKED", "USER_CONFIRMED", "")), false).isSuccess());
        verify(goals, never()).updateProgress(any(), any());
    }

    // 存储失败不替换内存快照，也不能被包装成成功。
    @Test
    void keepsSnapshotWhenDatabaseFails() {
        doThrow(new IllegalStateException("模拟数据库异常")).when(goals).updateProgress(any(), any());
        assertThrows(IllegalStateException.class,
                () -> service.update(input(change(1, "COMPLETED", "USER_CONFIRMED", "")), true));
        assertSame(original, context.require());
    }

    // 无专注上下文或用户不一致时，不能进入持久化服务。
    @Test
    void rejectsChatModeAndWrongUser() {
        String input = input(change(1, "COMPLETED", "USER_CONFIRMED", ""));
        context.clear();
        assertEquals("STEP_ACCESS_DENIED", service.update(input, true).getErrorCode());
        context.bind(original);
        BaseContext.setCurrentId(8L);
        assertEquals("STEP_ACCESS_DENIED", service.update(input, true).getErrorCode());
        verify(goals, never()).updateProgress(any(), any());
    }

    // 完成一次请求后，旧用户原话不能泄漏到另一次请求。
    @Test
    void clearsAndRebindsDialogueEvidence() {
        String quote = "我能说明核心线程与最大线程的区别。";
        assertTrue(context.containsUserEvidence(quote));
        context.clear();
        context.bind(original);
        assertFalse(context.containsUserEvidence(quote));
        context.bindDialogue(List.of(LlmMessage.user("新问题")));
        assertFalse(context.containsUserEvidence(quote));
    }

    // 工具声明接入通用审批；展示内容含完成条件而不是只有一条“是否通过”。
    @Test
    void exposesApprovalPolicyAndReadableReason() {
        var tool = new UpdateTaskProgressTool(service);
        assertTrue(tool.requiresUserApproval());
        assertTrue(tool.requiresExclusiveBatch());
        assertTrue(tool.isContextScopedTool());
        String reason = tool.approvalReason(input(change(1, "COMPLETED", "USER_CONFIRMED", "")));
        assertTrue(reason.contains("能说明核心线程与最大线程的区别"));
        assertTrue(reason.contains("用户选择继续，未验证掌握"));
        assertTrue(reason.length() <= 500);
        assertNotNull(tool.parametersSchema().get("properties"));
    }

    // 只为测试模拟一次成功提交，保留原对象，便于检查是否提前修改了快照。
    private SessionGoalSnapshot committed(UpdateTaskPlanRequest request) {
        var updated = json.readValue(json.writeValueAsString(context.require()), SessionGoalSnapshot.class);
        AgentTaskPlan plan = updated.getCurrentPlan();
        plan.setVersion(plan.getVersion() + 1);
        for (int i = 0; i < plan.getSteps().size(); i++) {
            plan.getSteps().get(i).setStatus(request.getSteps().get(i).getStatus());
            plan.getSteps().get(i).setResultSummary(request.getSteps().get(i).getResultSummary());
        }
        updated.setGoals(List.of(plan));
        return updated;
    }

    // 创建与生产请求相同的 JSON，避免手拼用户原话导致转义错误。
    @SafeVarargs
    private final String input(Map<String, String>... changes) {
        return json.writeValueAsString(Map.of("updates", Arrays.asList(changes)));
    }

    // 按当前版本生成步骤引用，测试可显式替换它来模拟过期请求。
    private Map<String, String> change(int position, String status, String basis, String evidence) {
        AgentTaskPlan plan = context.require().getCurrentPlan();
        return new LinkedHashMap<>(Map.of("stepRef", TaskProgressToolService.stepRef(plan, plan.getSteps().get(position - 1)),
                "status", status, "reason", "本轮已给出说明，申请按用户选择推进", "completionBasis", basis, "userEvidence", evidence));
    }

    // 创建两步的当前计划，不需要真实数据库或测试专用生产构造器。
    private SessionGoalSnapshot snapshot() {
        String id = UUID.randomUUID().toString();
        List<AgentTaskStep> steps = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            AgentTaskStep step = new AgentTaskStep();
            step.setStepId(UUID.randomUUID().toString()); step.setPlanId(id); step.setPosition(i);
            step.setDescription("线程池步骤" + i); step.setCompletionCriteria("能说明核心线程与最大线程的区别");
            step.setStatus(i == 1 ? AgentTaskStepStatus.IN_PROGRESS : AgentTaskStepStatus.PENDING);
            steps.add(step);
        }
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setPlanId(id); plan.setUserId(7L); plan.setSessionId(9L); plan.setGoalNumber(1);
        plan.setGoal("理解线程池"); plan.setVersion(1); plan.setSteps(steps);
        SessionFocusState state = new SessionFocusState();
        state.setUserId(7L); state.setSessionId(9L); state.setActivePlanId(id); state.setVersion(2); state.setNextGoalNumber(2);
        SessionGoalSnapshot snapshot = new SessionGoalSnapshot();
        snapshot.setState(state); snapshot.setCurrentPlan(plan); snapshot.setGoals(List.of(plan));
        return snapshot;
    }
}
