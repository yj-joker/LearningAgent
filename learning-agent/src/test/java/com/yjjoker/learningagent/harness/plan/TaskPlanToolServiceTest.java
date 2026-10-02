package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import com.yjjoker.learningagent.harness.plan.model.SessionFocusState;
import com.yjjoker.learningagent.harness.plan.model.SessionGoalSnapshot;
import com.yjjoker.learningagent.harness.plan.service.AgentTaskPlanService;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalContext;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalService;
import com.yjjoker.learningagent.harness.plan.service.TaskPlanToolService;
import com.yjjoker.learningagent.harness.plan.service.TaskProgressToolService;
import com.yjjoker.learningagent.repository.AgentTaskPlanRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// 使用真实 JSON 解析和 stepRef 校验；数据库事务服务用 Mock 隔离。
class TaskPlanToolServiceTest {
    private final SessionGoalContext context = new SessionGoalContext();
    private final SessionGoalService goals = mock(SessionGoalService.class);
    private final TaskPlanToolService service = new TaskPlanToolService(context, goals);
    private final JsonMapper json = new JsonMapper();
    private SessionGoalSnapshot original;

    // 固定当前用户和两步计划，模拟主 AgentLoop 已加载专注快照。
    @BeforeEach
    void setup() {
        BaseContext.setCurrentId(7L);
        original = snapshot();
        context.bind(original);
        context.bindDialogue(List.of(LlmMessage.user("请把计划拆得更细")));
        doNothing().when(goals).validateProgress(any(), any());
    }

    // 清理 ThreadLocal，避免测试线程复用旧专注上下文。
    @AfterEach
    void clear() {
        context.clear();
        BaseContext.removeCurrentId();
    }

    // 审批前允许新增和重排，但不调用正式更新服务，也不改变当前快照。
    @Test
    void validatesAddAndReorderWithoutWriting() {
        String input = input(
                step(ref(2), "先验证事务", "能解释隔离级别", "PENDING", null),
                step(null, "补充并发案例", "能说明两个事务的执行顺序", "PENDING", null),
                step(ref(1), "理解数据库锁", "能给出并发例子", "IN_PROGRESS", null));

        assertTrue(service.update(input, false).isSuccess());
        verify(goals, never()).updateProgress(any(), any());
        assertEquals(1, original.getCurrentPlan().getVersion());
        assertEquals(2, original.getCurrentPlan().getSteps().size());
    }

    // 审批执行成功后绑定新的计划版本，并把新的步骤顺序返回给模型。
    @Test
    void commitsPlanChangeAndRefreshesSnapshot() {
        SessionGoalSnapshot changed = snapshot();
        changed.getCurrentPlan().setVersion(2);
        changed.getCurrentPlan().getSteps().get(0).setDescription("修改后的步骤");
        when(goals.updateProgress(any(), any())).thenReturn(changed);

        String input = input(
                step(ref(1), "修改后的步骤", "能给出并发例子", "IN_PROGRESS", null),
                step(ref(2), "第二步", "能说明提交时机", "CANCELED", "资料范围已调整"));

        var result = service.update(input, true);

        assertTrue(result.isSuccess());
        assertSame(changed, context.require());
        assertEquals(2, context.require().getCurrentPlan().getVersion());
        assertTrue(result.getContent().contains("goal-1-v2-step-1"));
        verify(goals).updateProgress(any(), any());
    }

    // 旧计划版本不能继续申请，避免审批等待期间覆盖别人提交的新版本。
    @Test
    void rejectsStaleVersion() {
        String input = inputWithVersion(0,
                step(ref(1), "理解数据库锁", "能给出并发例子", "IN_PROGRESS", null),
                step(ref(2), "第二步", "能说明提交时机", "PENDING", null));

        var result = service.update(input, false);

        assertEquals("INVALID_TASK_PLAN_UPDATE", result.getErrorCode());
        verifyNoInteractions(goals);
    }

    // 旧 stepRef、重复步骤和遗漏旧步骤都必须被拒绝，不能让模型凭位置猜测。
    @Test
    void rejectsStaleReferenceDuplicateAndOmittedStep() {
        assertFalse(service.update(input(
                step("goal-1-v2-step-1", "新描述", "新条件", "PENDING", null),
                step(ref(2), "第二步", "能说明提交时机", "PENDING", null)), false).isSuccess());
        assertFalse(service.update(input(
                step(ref(1), "理解数据库锁", "能给出并发例子", "IN_PROGRESS", null),
                step(ref(1), "重复", "重复", "PENDING", null)), false).isSuccess());
        assertFalse(service.update(input(
                step(ref(1), "理解数据库锁", "能给出并发例子", "IN_PROGRESS", null)), false).isSuccess());
        verify(goals, never()).updateProgress(any(), any());
    }

    // 工具声明必须进入通用审批和独占批次机制。
    @Test
    void exposesApprovalPolicy() {
        var tool = new com.yjjoker.learningagent.harness.tool.impl.UpdateTaskPlanTool(service);
        assertTrue(tool.requiresUserApproval());
        assertTrue(tool.requiresExclusiveBatch());
        assertTrue(tool.isContextScopedTool());
        assertNotNull(tool.parametersSchema().get("properties"));
    }

    // 创建当前测试所需的两步专注快照，不在生产代码增加测试构造器。
    private SessionGoalSnapshot snapshot() {
        String planId = UUID.randomUUID().toString();
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setPlanId(planId);
        plan.setUserId(7L);
        plan.setSessionId(9L);
        plan.setGoalNumber(1);
        plan.setGoal("理解数据库锁");
        plan.setVersion(1);
        List<AgentTaskStep> steps = new ArrayList<>();
        steps.add(stepModel(planId, 1, "理解数据库锁", "能给出并发例子", AgentTaskStepStatus.IN_PROGRESS));
        steps.add(stepModel(planId, 2, "第二步", "能说明提交时机", AgentTaskStepStatus.PENDING));
        plan.setSteps(steps);
        SessionFocusState state = new SessionFocusState();
        state.setUserId(7L);
        state.setSessionId(9L);
        state.setActivePlanId(planId);
        state.setVersion(1);
        state.setNextGoalNumber(2);
        SessionGoalSnapshot snapshot = new SessionGoalSnapshot();
        snapshot.setState(state);
        snapshot.setCurrentPlan(plan);
        snapshot.setGoals(List.of(plan));
        return snapshot;
    }

    // 生成后端步骤对象，测试中的引用仍通过正式 stepRef 方法计算。
    private AgentTaskStep stepModel(String planId, int position, String description,
                                    String criteria, AgentTaskStepStatus status) {
        AgentTaskStep step = new AgentTaskStep();
        step.setStepId(UUID.randomUUID().toString());
        step.setPlanId(planId);
        step.setPosition(position);
        step.setDescription(description);
        step.setCompletionCriteria(criteria);
        step.setStatus(status);
        return step;
    }

    // 用当前计划版本生成模型可以看到的步骤引用。
    private String ref(int position) {
        return TaskProgressToolService.stepRef(original.getCurrentPlan(), original.getCurrentPlan().getSteps().get(position - 1));
    }

    // 生成结构化步骤参数，避免手写 JSON 转义错误。
    private String input(Object... steps) {
        return inputWithVersion(1, steps);
    }

    // 允许测试显式构造过期版本。
    private String inputWithVersion(long version, Object... steps) {
        return json.writeValueAsString(java.util.Map.of("expectedVersion", version, "steps", List.of(steps)));
    }

    // 生成计划修改中的一个步骤对象。
    private java.util.Map<String, Object> step(String stepRef, String description, String criteria,
                                                String status, String resultSummary) {
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("stepRef", stepRef);
        value.put("description", description);
        value.put("completionCriteria", criteria);
        value.put("status", status);
        value.put("resultSummary", resultSummary);
        return value;
    }
}
