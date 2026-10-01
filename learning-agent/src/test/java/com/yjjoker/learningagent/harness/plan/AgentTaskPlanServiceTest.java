package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskStepRequest;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import com.yjjoker.learningagent.harness.plan.service.AgentTaskPlanService;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.AgentTaskPlanRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 单元测试只验证服务边界；真实 SQL 与事务回滚由 MySQL 集成测试验证。
class AgentTaskPlanServiceTest {
    private static final Long USER_ID = 101L;
    private static final Long SESSION_ID = 201L;
    private static final String RUN_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private final AgentTaskPlanRepository repository = mock(AgentTaskPlanRepository.class);
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final AgentTaskPlanService service = new AgentTaskPlanService(repository, sessions);
    private LearningSession session;

    // 每个用例从当前用户自己的活动会话开始，不依赖已启动的后端。
    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
        session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        when(repository.insertPlan(any())).thenReturn(1);
        when(repository.insertStep(any())).thenReturn(1);
    }

    // 清理线程中的用户，避免后面的测试误用当前身份。
    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    // 归属、编号、版本和状态均由后端生成，输入列表只决定初始顺序。
    @Test
    void shouldCreateNormalizedPlanWithStableStepIds() {
        CreateTaskPlanRequest request = request();
        request.setGoal("  生成 Java 练习题  " );
        AgentTaskPlan plan = service.create(RUN_ID.toUpperCase(), SESSION_ID, request);
        assertEquals(RUN_ID, plan.getRunId());
        assertEquals(USER_ID, plan.getUserId());
        assertEquals(SESSION_ID, plan.getSessionId());
        assertEquals("生成 Java 练习题", plan.getGoal());
        assertEquals("五道题，附答案", plan.getConstraints());
        assertEquals(1L, plan.getVersion());
        assertEquals(List.of(1, 2), plan.getSteps().stream().map(AgentTaskStep::getPosition).toList());
        assertEquals(2, plan.getSteps().stream().map(AgentTaskStep::getStepId).distinct().count());
        for (AgentTaskStep step : plan.getSteps()) {
            assertDoesNotThrow(() -> UUID.fromString(step.getStepId()));
            assertEquals(RUN_ID, step.getRunId());
            assertEquals(AgentTaskStepStatus.PENDING, step.getStatus());
            assertNull(step.getResultSummary());
            assertEquals(plan.getCreatedAt(), step.getCreatedAt());
        }
        // 修改调用方的 DTO，不应反过来修改已经返回的计划对象。
        request.getSteps().getFirst().setDescription("输入已变化");
        assertEquals("检索资料", plan.getSteps().getFirst().getDescription());
        verify(repository).insertPlan(plan);
        verify(repository, times(2)).insertStep(any());
    }

    // 可选限制允许不填，避免数据库混用空串和 null。
    @Test
    void shouldNormalizeEmptyConstraintsToNull() {
        CreateTaskPlanRequest request = request();
        request.setConstraints("  \n " );
        assertNull(service.create(RUN_ID, SESSION_ID, request).getConstraints());
    }

    // 未登录不能查询会话或接触计划数据。
    @Test
    void shouldRejectUnauthenticatedAccess() {
        BaseContext.removeCurrentId();
        assertThrows(LearningSessionStatusException.class, () -> service.create(RUN_ID, SESSION_ID, request()));
        assertThrows(LearningSessionStatusException.class, () -> service.load(RUN_ID, SESSION_ID));
        verifyNoInteractions(sessions, repository);
    }

    // 知道他人的会话编号也不能创建或读取计划。
    @Test
    void shouldRejectAnotherUsersSession() {
        session.setUserId(USER_ID + 1);
        assertThrows(LearningSessionStatusException.class, () -> service.create(RUN_ID, SESSION_ID, request()));
        assertThrows(LearningSessionStatusException.class, () -> service.load(RUN_ID, SESSION_ID));
        verifyNoInteractions(repository);
    }

    // 不存在的会话与他人会话使用相同拒绝边界。
    @Test
    void shouldRejectMissingSession() {
        when(sessions.findSessionById(SESSION_ID)).thenReturn(Optional.empty());
        assertThrows(LearningSessionStatusException.class, () -> service.create(RUN_ID, SESSION_ID, request()));
        verifyNoInteractions(repository);
    }

    // 已取消会话相当于用户删除了会话，计划不能绕开这个限制。
    @Test
    void shouldRejectCanceledSessionForCreateAndRead() {
        session.setStatus(LearningSessionStatusEnum.CANCELED);
        assertThrows(LearningSessionStatusException.class, () -> service.create(RUN_ID, SESSION_ID, request()));
        assertThrows(LearningSessionStatusException.class, () -> service.load(RUN_ID, SESSION_ID));
        verifyNoInteractions(repository);
    }

    // 已完成会话可以查看旧计划，但不能新增计划。
    @Test
    void shouldAllowReadButNotCreateInCompletedSession() {
        AgentTaskPlan saved = service.create(RUN_ID, SESSION_ID, request());
        clearInvocations(repository);
        session.setStatus(LearningSessionStatusEnum.COMPLETED);
        stubLoad(saved);
        assertSame(saved, service.load(RUN_ID, SESSION_ID));
        assertThrows(LearningSessionStatusException.class, () -> service.create(RUN_ID, SESSION_ID, request()));
        verify(repository, never()).insertPlan(any());
    }

    // 拒绝空编号、缩写 UUID 和普通文本，不让错误任务编号进入数据库。
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-run", "1-1-1-1-1", " aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"})
    void shouldRejectMalformedRunId(String runId) {
        assertThrows(ClientDataErrorException.class, () -> service.create(runId, SESSION_ID, request()));
        assertThrows(ClientDataErrorException.class, () -> service.load(runId, SESSION_ID));
        verifyNoInteractions(repository);
    }

    // 会话编号必须为正，不能把空值或占位编号当成真实会话。
    @Test
    void shouldRejectInvalidSessionId() {
        for (Long id : java.util.Arrays.asList(null, 0L, -1L)) {
            assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, id, request()));
        }
        verifyNoInteractions(repository);
    }

    // 目标是计划的必要信息，空白目标必须在写库前拦住。
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t"})
    void shouldRejectMissingGoal(String goal) {
        CreateTaskPlanRequest request = request();
        request.setGoal(goal);
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, request));
        verifyNoInteractions(repository);
    }

    // 空计划、空步骤列表和超过上限的步骤数都不能保存。
    @Test
    void shouldRejectMissingOrExcessiveSteps() {
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, null));
        CreateTaskPlanRequest request = request();
        request.setSteps(null);
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, request));
        request.setSteps(List.of());
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, request));
        request.setSteps(Collections.nCopies(AgentTaskPlanService.MAX_STEPS + 1, step("检索", "找到来源")));
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, request));
        verifyNoInteractions(repository);
    }

    // 后面的步骤无效时，前面的合法步骤和计划也不能提前写入。
    @Test
    void shouldValidateEveryStepBeforeWritingAnything() {
        CreateTaskPlanRequest request = request();
        request.getSteps().getLast().setCompletionCriteria(" " );
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, request));
        request.setSteps(java.util.Arrays.asList(step("检索", "有来源"), null));
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, request));
        verifyNoInteractions(repository);
    }

    // 目标、限制、内容和完成条件分别限长，不允许静默截断用户要求。
    @Test
    void shouldRejectOversizedText() {
        CreateTaskPlanRequest request = request();
        request.setGoal("字".repeat(AgentTaskPlanService.MAX_GOAL_LENGTH + 1));
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, request));
        request.setGoal("生成练习");
        request.setConstraints("字".repeat(AgentTaskPlanService.MAX_CONSTRAINTS_LENGTH + 1));
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, request));
        request.setConstraints(null);
        request.getSteps().getFirst().setDescription("字".repeat(AgentTaskPlanService.MAX_STEP_TEXT_LENGTH + 1));
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, request));
        request.getSteps().getFirst().setDescription("检索资料");
        request.getSteps().getFirst().setCompletionCriteria("字".repeat(AgentTaskPlanService.MAX_STEP_TEXT_LENGTH + 1));
        assertThrows(ClientDataErrorException.class, () -> service.create(RUN_ID, SESSION_ID, request));
        verifyNoInteractions(repository);
    }

    // 上限以内的中文和表情按字符计数，不误把一个表情当成两个数据库字符。
    @Test
    void shouldAcceptBoundaryLengthsAndStepCount() {
        CreateTaskPlanRequest request = request();
        request.setGoal("字".repeat(AgentTaskPlanService.MAX_GOAL_LENGTH));
        request.setConstraints("字".repeat(AgentTaskPlanService.MAX_CONSTRAINTS_LENGTH));
        request.setSteps(Collections.nCopies(AgentTaskPlanService.MAX_STEPS,
                step("📚".repeat(AgentTaskPlanService.MAX_STEP_TEXT_LENGTH), "完成")));
        AgentTaskPlan plan = service.create(RUN_ID, SESSION_ID, request);
        assertEquals(AgentTaskPlanService.MAX_STEPS, plan.getSteps().size());
        assertEquals(AgentTaskPlanService.MAX_STEPS, plan.getSteps().stream().map(AgentTaskStep::getStepId).distinct().count());
    }

    // 主键冲突只能拒绝，不能继续插步骤或覆盖现有计划。
    @Test
    void shouldRejectDuplicatePlanWithoutWritingSteps() {
        when(repository.insertPlan(any())).thenThrow(new DuplicateKeyException("duplicate"));
        ClientDataErrorException error = assertThrows(ClientDataErrorException.class,
                () -> service.create(RUN_ID, SESSION_ID, request()));
        assertTrue(error.getMessage().contains("不能重复创建"));
        verify(repository, never()).insertStep(any());
    }

    // 写入数量异常不能假装成功；数据库回滚在集成测试中另行验证。
    @Test
    void shouldRejectMissingAffectedRow() {
        when(repository.insertStep(any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.create(RUN_ID, SESSION_ID, request()));
    }

    // 查询目标和查询步骤使用同一组用户、会话、任务范围。
    @Test
    void shouldLoadPlanWithinCurrentOwnerScope() {
        AgentTaskPlan saved = service.create(RUN_ID, SESSION_ID, request());
        stubLoad(saved);
        assertSame(saved, service.load(RUN_ID, SESSION_ID));
        verify(repository).findPlan(USER_ID, SESSION_ID, RUN_ID);
        verify(repository).findSteps(USER_ID, SESSION_ID, RUN_ID);
    }

    // 计划不存在时不再读取步骤，错误信息也不暴露其他用户的任务。
    @Test
    void shouldRejectMissingPlanBeforeReadingSteps() {
        when(repository.findPlan(USER_ID, SESSION_ID, RUN_ID)).thenReturn(Optional.empty());
        assertThrows(ClientDataErrorException.class, () -> service.load(RUN_ID, SESSION_ID));
        verify(repository, never()).findSteps(any(), any(), any());
    }

    // 数据库中出现半份计划时明确报错，不向上游返回一个看似正常的空计划。
    @Test
    void shouldRejectPlanWithoutSteps() {
        AgentTaskPlan saved = service.create(RUN_ID, SESSION_ID, request());
        when(repository.findPlan(USER_ID, SESSION_ID, RUN_ID)).thenReturn(Optional.of(saved));
        when(repository.findSteps(USER_ID, SESSION_ID, RUN_ID)).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> service.load(RUN_ID, SESSION_ID));
    }

    // 为读取用例准备完整记录，不影响真实数据库。
    private void stubLoad(AgentTaskPlan plan) {
        when(repository.findPlan(USER_ID, SESSION_ID, RUN_ID)).thenReturn(Optional.of(plan));
        when(repository.findSteps(USER_ID, SESSION_ID, RUN_ID)).thenReturn(plan.getSteps());
    }

    // 构造两个可执行步骤，供各个边界用例做少量修改。
    private CreateTaskPlanRequest request() {
        CreateTaskPlanRequest request = new CreateTaskPlanRequest();
        request.setGoal("生成 Java 练习题");
        request.setConstraints("五道题，附答案");
        request.setSteps(new ArrayList<>(List.of(step("检索资料", "找到相关来源"), step("生成题目", "五道题均有答案"))));
        return request;
    }

    // 只提供步骤正文与完成条件，状态和编号不由测试输入指定。
    private CreateTaskStepRequest step(String description, String criteria) {
        CreateTaskStepRequest step = new CreateTaskStepRequest();
        step.setDescription(description);
        step.setCompletionCriteria(criteria);
        return step;
    }
}
