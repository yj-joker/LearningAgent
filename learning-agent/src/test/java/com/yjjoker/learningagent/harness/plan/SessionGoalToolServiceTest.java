package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.harness.plan.model.*;
import com.yjjoker.learningagent.harness.plan.service.*;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.entity.LearningPlanDraft;
import com.yjjoker.learningagent.entity.LearningPlanDraftStep;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskPlanRequest;
import com.yjjoker.learningagent.projectenum.LearningPlanStepProgressStatus;
import com.yjjoker.learningagent.vo.LearningPlanProgressVO;
import com.yjjoker.learningagent.vo.LearningPlanStepProgressVO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 工具边界测试：只替换数据库服务，参数解析和当前请求隔离使用真实代码。
class SessionGoalToolServiceTest {
    private static final String CREATE = """
            {"goal":"学习数据库锁","steps":[{"description":"解释行锁","completionCriteria":"给出并发例子"}]}
            """;
    private final SessionGoalContext context = new SessionGoalContext();
    private final SessionGoalService goals = mock(SessionGoalService.class);
    private final SessionGoalToolService tools = new SessionGoalToolService(context, goals);
    private SessionGoalSnapshot initial;

    // 当前请求属于用户 7 的会话 9，先固定原目标。
    @BeforeEach
    void setup() {
        BaseContext.setCurrentId(7L);
        initial = snapshot(1, "完善 Harness");
        context.bind(initial);
    }

    // 模拟 Harness 的 finally，线程复用前清掉用户和目标。
    @AfterEach
    void clear() {
        context.clear();
        BaseContext.removeCurrentId();
    }

    // 预检不写数据库，不切换当前上下文。
    @Test
    void validatesCreateWithoutWriting() {
        assertTrue(tools.create(CREATE, false).isSuccess());
        verify(goals, never()).create(any(), any(), any(), any());
        assertSame(initial, context.require());
    }

    // 事务服务成功后，本轮模型上下文才可以改成新目标。
    @Test
    void bindsCommittedGoalAfterCreate() {
        SessionGoalSnapshot changed = snapshot(2, "学习数据库锁");
        when(goals.create(same(initial), any(), isNull(), isNull())).thenReturn(changed);
        var result = tools.create(CREATE, true);
        assertTrue(result.isSuccess());
        assertSame(changed, context.require());
        assertTrue(result.getContent().contains("goal-2"));
    }

    // 存储异常必须向外传播，不能提前绑定没提交的新目标。
    @Test
    void keepsOldContextWhenPersistenceFails() {
        when(goals.create(any(), any(), any(), any())).thenThrow(new IllegalStateException("模拟存储故障"));
        assertThrows(IllegalStateException.class, () -> tools.create(CREATE, true));
        assertSame(initial, context.require());
    }

    // 与规划器使用同一解析器，防止审批前通过而正式保存时才发现结构错误。
    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"goal\":3,\"steps\":[]}",
            "{\"goal\":\"A\",\"goal\":\"B\",\"steps\":[]}",
            "{\"goal\":\"A\",\"steps\":[],\"userId\":8}", "{} {}"})
    void rejectsInvalidCreateArguments(String input) {
        assertFalse(tools.create(input, false).isSuccess());
        verify(goals, never()).create(any(), any());
    }

    // 切换只接收 goalRef，预检不能调用正式切换服务。
    @Test
    void validatesSwitchAndThenBindsItsResult() {
        SessionGoalSnapshot target = snapshot(2, "数据库");
        assertTrue(tools.switchTo("{\"goalRef\":\"goal-2\"}", false).isSuccess());
        verify(goals, never()).switchTo(any(), any());
        when(goals.switchTo(initial, "goal-2")).thenReturn(target);
        assertTrue(tools.switchTo("{\"goalRef\":\"goal-2\"}", true).isSuccess());
        assertSame(target, context.require());
    }

    // 后端拒绝额外参数与错误类型，不能通过伪造会话或数据库 ID 切换目标。
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"goalRef\":7}", "{\"goalRef\":\"goal-2\",\"sessionId\":99}",
            "{\"goalRef\":\"goal-1\",\"goalRef\":\"goal-2\"}", "{} {}"})
    void rejectsInvalidSwitchArguments(String input) {
        assertFalse(tools.switchTo(input, false).isSuccess());
        verify(goals, never()).switchTo(any(), any());
    }

    // 没有专注上下文时，模型即使调用目标工具也不会进入数据库服务。
    @Test
    void rejectsChatModeAndForeignUser() {
        context.clear();
        assertEquals("GOAL_ACCESS_DENIED", tools.create(CREATE, true).getErrorCode());
        context.bind(initial);
        BaseContext.setCurrentId(8L);
        assertEquals("GOAL_ACCESS_DENIED", tools.list("{}").getErrorCode());
        verifyNoInteractions(goals);
    }

    // 索引只暴露短引用，不发送真实计划 UUID。
    @Test
    void listsOnlyScopedPublicReferences() {
        when(goals.load(9L)).thenReturn(initial);
        var result = tools.list("{}");
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("goal-1"));
        assertFalse(result.getContent().contains(initial.getCurrentPlan().getPlanId()));
    }

    // 详细进度查询返回当前计划的全部步骤和数据库状态，不返回内部计划 UUID。
    @Test
    void returnsCurrentGoalProgressFromFreshSnapshot() {
        AgentTaskStep step = new AgentTaskStep();
        step.setStepId(UUID.randomUUID().toString());
        step.setPlanId(initial.getCurrentPlan().getPlanId());
        step.setPosition(1);
        step.setDescription("理解行锁");
        step.setCompletionCriteria("能给出并发例子");
        step.setStatus(AgentTaskStepStatus.PENDING);
        initial.getCurrentPlan().setSteps(List.of(step));
        when(goals.load(9L)).thenReturn(initial);

        var result = tools.progress("{}");

        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("\"goalRef\":\"goal-1\""));
        assertTrue(result.getContent().contains("\"status\":\"PENDING\""));
        assertTrue(result.getContent().contains("理解行锁"));
        assertFalse(result.getContent().contains(initial.getCurrentPlan().getPlanId()));
        assertSame(initial, context.require());
    }

    // 工具目录必须公开阶段参数，模型才有能力创建第二阶段任务。
    @Test
    void exposesLearningStageBindingInToolSchema() {
        var properties = (java.util.Map<?, ?>) tools.createSchema().get("properties");
        assertTrue(properties.containsKey("learningScope"));
        assertTrue(properties.containsKey("learningPlanStageRef"));
    }

    // 第一阶段已确认时，新任务必须绑定第二阶段；错误引用在审批前拒绝。
    @Test
    void validatesNextStageBeforeApprovalWithoutChangingCurrentTask() {
        LearningPlanDraft draft = new LearningPlanDraft();
        draft.setDraftRef("test-draft");
        draft.setVersion(2);
        draft.setSemanticVersion(1);
        LearningPlanDraftStep first = stage("stage-one", 1);
        LearningPlanDraftStep second = stage("stage-two", 2);
        draft.setSteps(List.of(first, second));
        initial.getState().setLearningPlanDraftRef(draft.getDraftRef());
        context.bindLearningPlan(draft);
        context.bindLearningPlanProgress(new LearningPlanProgressVO(draft.getDraftRef(), "测试计划", 2, 1,
                List.of(progress(first, LearningPlanStepProgressStatus.CONFIRMED),
                        progress(second, LearningPlanStepProgressStatus.NOT_STARTED))));
        // 只调用真实的纯参数校验，数据库服务仍是替身。
        doCallRealMethod().when(goals).validateCreateBinding(any(), any(), any(), any());
        String request = """
                {"goal":"学习第二阶段","steps":[{"description":"教学和练习","completionCriteria":"解释并正确作答"}],
                 "learningScope":"CURRENT_STAGE","learningPlanStageRef":"stage-two"}
                """;
        assertTrue(tools.create(request, false).isSuccess());
        assertFalse(tools.create(request.replace("stage-two", "stage-one"), false).isSuccess());
        assertFalse(tools.create(request.replace("stage-two", "foreign-stage"), false).isSuccess());
        assertFalse(tools.create(CREATE, false).isSuccess());
        assertSame(initial, context.require());
        verify(goals, never()).create(any(), any(), any(), any());
    }

    // 构造两个稳定阶段引用，便于检查前后阶段归属。
    private LearningPlanDraftStep stage(String ref, int position) {
        LearningPlanDraftStep step = new LearningPlanDraftStep();
        step.setStepRef(ref);
        step.setPosition(position);
        step.setDescription(ref);
        step.setCompletionCriteria("解释并正确作答");
        return step;
    }

    // 固定数据库进度快照，不让测试依赖模型自然语言判断。
    private LearningPlanStepProgressVO progress(LearningPlanDraftStep step, LearningPlanStepProgressStatus status) {
        return new LearningPlanStepProgressVO(step.getStepRef(), step.getPosition(), step.getDescription(),
                step.getCompletionCriteria(), status, null, null, null, 2, 1, 1);
    }

    // 简短样例只保留工具测试需要的字段，不在生产类增加测试构造器。
    private SessionGoalSnapshot snapshot(int number, String title) {
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setPlanId(UUID.randomUUID().toString());
        plan.setGoalNumber(number);
        plan.setGoal(title);
        plan.setUserId(7L);
        plan.setSessionId(9L);
        plan.setVersion(1);
        SessionFocusState state = new SessionFocusState();
        state.setUserId(7L);
        state.setSessionId(9L);
        state.setActivePlanId(plan.getPlanId());
        state.setVersion(number + 1L);
        state.setNextGoalNumber(number + 1);
        SessionGoalSnapshot snapshot = new SessionGoalSnapshot();
        snapshot.setState(state);
        snapshot.setCurrentPlan(plan);
        snapshot.setGoals(List.of(plan));
        return snapshot;
    }
}
