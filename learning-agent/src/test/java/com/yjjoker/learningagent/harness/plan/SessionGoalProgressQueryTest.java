package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.controller.AgentSessionProgressController;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import com.yjjoker.learningagent.harness.plan.model.SessionFocusState;
import com.yjjoker.learningagent.harness.plan.service.AgentTaskPlanService;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalService;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.AgentTaskPlanRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 查询使用真实服务和 MVC，数据库替身检查归属、只读行为及空目标，不调用模型。
class SessionGoalProgressQueryTest {
    private final AgentTaskPlanRepository repository = mock(AgentTaskPlanRepository.class);
    private final AgentTaskPlanService plans = mock(AgentTaskPlanService.class);
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final SessionGoalService service = new SessionGoalService(repository, plans, sessions);
    private LearningSession session;

    // 准备当前用户的专注会话，实际目标数据由各场景指定。
    @BeforeEach
    void setup() {
        BaseContext.setCurrentId(7L);
        session = new LearningSession();
        session.setId(9L);
        session.setUserId(7L);
        session.setMode(AgentMode.FOCUS);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(9L)).thenReturn(Optional.of(session));
    }

    // 当前用户信息不能残留到其他测试。
    @AfterEach
    void clear() {
        BaseContext.removeCurrentId();
    }

    // 首轮尚未规划时返回空，查询不能创建焦点表或任务。
    @Test
    void returnsNullWithoutCreatingGoal() {
        when(repository.findFocus(7L, 9L)).thenReturn(Optional.empty());
        assertNull(service.getProgress(9L));
        verify(repository).findFocus(7L, 9L);
        verify(repository, never()).ensureFocus(anyLong(), anyLong());
        verifyNoInteractions(plans);
    }

    // 已完成会话也展示数据库里的目标和状态，不把步骤完成解释为已经掌握知识。
    @Test
    void restoresCompletedSessionProgressWithoutWrites() throws Exception {
        session.setStatus(LearningSessionStatusEnum.COMPLETED);
        SessionFocusState state = new SessionFocusState();
        state.setUserId(7L);
        state.setSessionId(9L);
        state.setActivePlanId("saved-plan");
        AgentTaskStep step = new AgentTaskStep();
        step.setPosition(1);
        step.setDescription("练习索引查询");
        step.setCompletionCriteria("解释执行计划");
        step.setStatus(AgentTaskStepStatus.COMPLETED);
        step.setResultSummary("已确认练习完成");
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setPlanId("saved-plan");
        plan.setGoalNumber(2);
        plan.setGoal("理解索引");
        plan.setVersion(3);
        plan.setSteps(List.of(step));
        when(repository.findFocus(7L, 9L)).thenReturn(Optional.of(state));
        when(repository.findGoals(7L, 9L)).thenReturn(List.of(plan));
        when(plans.load("saved-plan", 9L)).thenReturn(plan);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentSessionProgressController(service)).build();
        mvc.perform(get("/agent/sessions/9/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.goal").value("理解索引"))
                .andExpect(jsonPath("$.data.steps[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.steps[0].stepRef").value("goal-2-v3-step-1"));
        verify(repository, never()).ensureFocus(anyLong(), anyLong());
        verify(plans, never()).create(anyString(), anyLong(), any());
        verify(plans, never()).update(anyString(), anyLong(), any());
    }

    // 其他用户、已删除会话和其他模式都在读取目标前拒绝。
    @Test
    void rejectsInaccessibleOrDifferentModeSessions() {
        session.setUserId(8L);
        assertThrows(LearningSessionStatusException.class, () -> service.getProgress(9L));
        session.setUserId(7L);
        session.setStatus(LearningSessionStatusEnum.CANCELED);
        assertThrows(LearningSessionStatusException.class, () -> service.getProgress(9L));
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        for (AgentMode mode : List.of(AgentMode.CHAT, AgentMode.COURSE)) {
            session.setMode(mode);
            assertThrows(LearningSessionStatusException.class, () -> service.getProgress(9L));
        }
        verifyNoInteractions(repository, plans);
    }

    // 无登录身份和非法编号不能访问数据库；会话不存在时也不读取目标。
    @Test
    void rejectsMissingIdentityAndInvalidSession() {
        BaseContext.removeCurrentId();
        assertThrows(LearningSessionStatusException.class, () -> service.getProgress(9L));
        BaseContext.setCurrentId(7L);
        assertThrows(LearningSessionStatusException.class, () -> service.getProgress(0L));
        when(sessions.findSessionById(10L)).thenReturn(Optional.empty());
        assertThrows(LearningSessionStatusException.class, () -> service.getProgress(10L));
        verifyNoInteractions(repository, plans);
    }
}
