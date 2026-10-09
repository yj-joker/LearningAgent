package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.entity.LearningPlanDraft;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.plan.model.SessionFocusState;
import com.yjjoker.learningagent.harness.plan.service.AgentTaskPlanService;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalService;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.AgentTaskPlanRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.service.LearningPlanDraftService;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 使用真实关联服务检查首次绑定、重复请求和改绑边界，数据库由 mock 替换。
class SessionLearningPlanBindingTest {
    private final AgentTaskPlanRepository repository = mock(AgentTaskPlanRepository.class);
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final LearningPlanDraftService drafts = mock(LearningPlanDraftService.class);
    private final SessionGoalService service = new SessionGoalService(
            repository, mock(AgentTaskPlanService.class), sessions, drafts);
    private SessionFocusState state;

    // 固定用户归属和已有目标，验证长期关联不会改变短期目标指针。
    @BeforeEach
    void setup() {
        BaseContext.setCurrentId(7L);
        LearningSession session = new LearningSession();
        session.setUserId(7L);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(9L)).thenReturn(Optional.of(session));
        state = new SessionFocusState();
        state.setUserId(7L);
        state.setSessionId(9L);
        state.setActivePlanId("existing-goal");
        when(repository.lockFocus(7L, 9L)).thenReturn(Optional.of(state));
    }

    // 清理线程身份，避免影响同线程运行的后续测试。
    @AfterEach
    void cleanup() {
        BaseContext.removeCurrentId();
    }

    // 首次关联规范引用并保存版本，原目标及其步骤无需重新创建。
    @Test
    void allowsFirstBindingWithoutChangingGoal() {
        LearningPlanDraft draft = new LearningPlanDraft();
        draft.setTitle("Java 学习计划");
        when(drafts.findActiveForUser(7L, "plan-a")).thenReturn(draft);
        when(repository.updateLearningPlanBinding(7L, 9L, "plan-a", 0L)).thenReturn(1);

        var result = service.bindLearningPlan(9L, " plan-a ", 0);

        assertEquals("plan-a", result.getDraftRef());
        assertEquals(1, result.getBindingVersion());
        assertEquals("existing-goal", state.getActivePlanId());
        verify(repository).updateLearningPlanBinding(7L, 9L, "plan-a", 0L);
        verify(repository, never()).changeFocus(any(), any(), any(), anyLong(), anyInt());
    }

    // 同计划重复提交保持原版本，保证正常重试不会被当成改绑。
    @Test
    void allowsSameBindingWithoutWriting() {
        state.setLearningPlanDraftRef("plan-a");
        state.setLearningPlanBindingVersion(1);
        LearningPlanDraft draft = new LearningPlanDraft();
        draft.setTitle("Java 学习计划");
        when(drafts.findActiveForUser(7L, "plan-a")).thenReturn(draft);

        var result = service.bindLearningPlan(9L, "plan-a", 1);

        assertEquals("plan-a", result.getDraftRef());
        assertEquals(1, result.getBindingVersion());
        verify(repository, never()).updateLearningPlanBinding(any(), any(), any(), anyLong());
    }

    // 空引用也会解除关联；禁止通过先解除再绑定来绕过限制。
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "plan-b"})
    void rejectsReplacementAndUnbinding(String requestedRef) {
        state.setLearningPlanDraftRef("plan-a");
        state.setLearningPlanBindingVersion(1);

        ClientDataErrorException error = assertThrows(ClientDataErrorException.class,
                () -> service.bindLearningPlan(9L, requestedRef, 1));

        assertTrue(error.getMessage().contains("不能更换或解除关联"));
        assertEquals("plan-a", state.getLearningPlanDraftRef());
        assertEquals("existing-goal", state.getActivePlanId());
        verifyNoInteractions(drafts);
        verify(repository, never()).updateLearningPlanBinding(any(), any(), any(), anyLong());
    }

    // 未关联时提交空选择仍是无操作，允许用户继续不带长期计划的专注会话。
    @Test
    void allowsUnboundSessionToRemainUnbound() {
        var result = service.bindLearningPlan(9L, null, 0);

        assertNull(result.getDraftRef());
        assertEquals(0, result.getBindingVersion());
        verify(repository, never()).updateLearningPlanBinding(any(), any(), any(), anyLong());
    }

    // 锁行后发现版本已变就拒绝，覆盖两个页面同时提交首次关联的边界。
    @Test
    void rejectsStaleBindingVersion() {
        state.setLearningPlanDraftRef("plan-a");
        state.setLearningPlanBindingVersion(1);

        ClientDataErrorException error = assertThrows(ClientDataErrorException.class,
                () -> service.bindLearningPlan(9L, "plan-b", 0));

        assertTrue(error.getMessage().contains("关联已变化"));
        verify(repository, never()).updateLearningPlanBinding(any(), any(), any(), anyLong());
    }
}
