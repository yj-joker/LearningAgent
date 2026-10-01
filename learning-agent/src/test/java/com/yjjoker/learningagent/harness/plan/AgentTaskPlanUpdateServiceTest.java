package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.harness.plan.dto.UpdateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.UpdateTaskStepRequest;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 检查更新规则；这里不宣称验证了回滚，真正回滚由 MySQL 测试确认。
class AgentTaskPlanUpdateServiceTest {
    private static final Long USER_ID = 101L;
    private static final Long SESSION_ID = 201L;
    private static final String PLAN_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private final AgentTaskPlanRepository repository = mock(AgentTaskPlanRepository.class);
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final AgentTaskPlanService service = new AgentTaskPlanService(repository, sessions);
    private final List<AgentTaskStep> originals = new ArrayList<>();
    private LearningSession session;

    // 模拟已锁定的计划和两步原记录，保持生产服务只有正常的依赖构造器。
    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
        session = new LearningSession();
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setPlanId(PLAN_ID);
        plan.setUserId(USER_ID);
        plan.setSessionId(SESSION_ID);
        plan.setGoal("生成五道练习");
        plan.setConstraints("必须附答案");
        plan.setVersion(2);
        originals.add(storedStep(1));
        originals.add(storedStep(2));
        when(repository.advanceVersion(eq(USER_ID), eq(SESSION_ID), eq(PLAN_ID), eq(1L), any())).thenReturn(1);
        when(repository.findPlanForUpdate(USER_ID, SESSION_ID, PLAN_ID)).thenReturn(Optional.of(plan));
        when(repository.findStepsForUpdate(USER_ID, SESSION_ID, PLAN_ID)).thenReturn(originals);
        when(repository.parkStepPositions(USER_ID, SESSION_ID, PLAN_ID, AgentTaskPlanService.MAX_STEPS)).thenReturn(2);
        when(repository.updateStep(eq(USER_ID), eq(SESSION_ID), any())).thenReturn(1);
        when(repository.insertStep(any())).thenReturn(1);
    }

    // 线程身份不能泄漏给下一个测试。
    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    // 一次修改可以同时重排、增加步骤和开始执行，但不能改变任务目标与限制。
    @Test
    void shouldUpdateStepsWithoutChangingGoalOrExistingIdentity() {
        UpdateTaskPlanRequest request = request();
        request.getSteps().getFirst().setStatus(AgentTaskStepStatus.IN_PROGRESS);
        Collections.swap(request.getSteps(), 0, 1);
        UpdateTaskStepRequest added = new UpdateTaskStepRequest();
        added.setDescription("补充例题");
        added.setCompletionCriteria("有解题过程");
        added.setStatus(AgentTaskStepStatus.PENDING);
        request.getSteps().add(added);
        AgentTaskPlan result = service.update(PLAN_ID, SESSION_ID, request);
        assertEquals(2, result.getVersion());
        assertEquals("生成五道练习", result.getGoal());
        assertEquals("必须附答案", result.getConstraints());
        assertEquals(originals.get(1).getStepId(), result.getSteps().get(0).getStepId());
        assertEquals(originals.get(0).getStepId(), result.getSteps().get(1).getStepId());
        assertEquals(originals.get(0).getCreatedAt(), result.getSteps().get(1).getCreatedAt());
        assertEquals(List.of(1, 2, 3), result.getSteps().stream().map(AgentTaskStep::getPosition).toList());
        assertNotNull(result.getSteps().get(2).getStepId());
        assertNull(added.getStepId());
        verify(repository).insertStep(result.getSteps().get(2));
        verify(repository, times(2)).updateStep(eq(USER_ID), eq(SESSION_ID), any());
    }

    // 完全没变的步骤保留更新时间；原对象也不能被更新请求修改。
    @Test
    void shouldPreserveUnchangedStepTimestampAndInputSnapshot() {
        UpdateTaskPlanRequest request = request();
        request.getSteps().getFirst().setDescription("更准确的检索内容");
        AgentTaskPlan result = service.update(PLAN_ID, SESSION_ID, request);
        assertEquals(originals.get(1).getUpdatedAt(), result.getSteps().get(1).getUpdatedAt());
        assertEquals("步骤1", originals.getFirst().getDescription());
        request.getSteps().getFirst().setDescription("后来又修改了输入");
        assertEquals("更准确的检索内容", result.getSteps().getFirst().getDescription());
    }

    // 旧版本或不属于当前范围的任务不能继续读写步骤，更不能自动改版本重试。
    @Test
    void shouldStopWhenVersionCheckFails() {
        when(repository.advanceVersion(eq(USER_ID), eq(SESSION_ID), eq(PLAN_ID), eq(1L), any())).thenReturn(0);
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request()));
        verify(repository, never()).findStepsForUpdate(any(), any(), any());
        verify(repository, never()).parkStepPositions(any(), any(), any(), anyInt());
        verify(repository, times(1)).advanceVersion(eq(USER_ID), eq(SESSION_ID), eq(PLAN_ID), eq(1L), any());
    }

    // 权限检查发生在版本更新之前，不能借请求编号修改别人的任务。
    @Test
    void shouldRejectUnauthenticatedAndForeignSessionUpdates() {
        BaseContext.removeCurrentId();
        assertThrows(LearningSessionStatusException.class, () -> service.update(PLAN_ID, SESSION_ID, request()));
        BaseContext.setCurrentId(USER_ID);
        session.setUserId(USER_ID + 1);
        assertThrows(LearningSessionStatusException.class, () -> service.update(PLAN_ID, SESSION_ID, request()));
        verifyNoInteractions(repository);
    }

    // 结束和取消的会话都不能修改计划。
    @ParameterizedTest
    @EnumSource(value = LearningSessionStatusEnum.class, names = {"COMPLETED", "CANCELED"})
    void shouldRejectInactiveSessionUpdates(LearningSessionStatusEnum status) {
        session.setStatus(status);
        assertThrows(LearningSessionStatusException.class, () -> service.update(PLAN_ID, SESSION_ID, request()));
        verifyNoInteractions(repository);
    }

    // 缺少版本、版本越界或列表大小不合要求时，不尝试占用数据库修改权。
    @Test
    void shouldRejectMalformedRequestsBeforeAdvancingVersion() {
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, null));
        for (Long version : Arrays.asList(null, 0L, -1L, Long.MAX_VALUE)) {
            UpdateTaskPlanRequest request = request();
            request.setExpectedVersion(version);
            assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        }
        UpdateTaskPlanRequest request = request();
        request.setSteps(null);
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        request.setSteps(List.of());
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        request.setSteps(Collections.nCopies(AgentTaskPlanService.MAX_STEPS + 1, new UpdateTaskStepRequest()));
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        verifyNoInteractions(repository);
    }

    // 固定编号不能被伪造、写空、重复引用，也不能省略某个旧步骤。
    @Test
    void shouldRejectUnknownDuplicateAndOmittedStepIds() {
        for (String invalidId : List.of(UUID.randomUUID().toString(), "", originals.get(1).getStepId())) {
            UpdateTaskPlanRequest request = request();
            request.getSteps().getFirst().setStepId(invalidId);
            assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        }
        UpdateTaskPlanRequest missing = request();
        missing.getSteps().removeLast();
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, missing));
        verify(repository, never()).parkStepPositions(any(), any(), any(), anyInt());
    }

    // 两个执行中状态必须在重排和写步骤之前被拒绝。
    @Test
    void shouldRejectMultipleRunningSteps() {
        UpdateTaskPlanRequest request = request();
        request.getSteps().forEach(step -> step.setStatus(AgentTaskStepStatus.IN_PROGRESS));
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        verify(repository, never()).parkStepPositions(any(), any(), any(), anyInt());
    }

    // 新步骤还没有执行，不能一新增就标记完成、受阻或正在执行。
    @ParameterizedTest
    @EnumSource(value = AgentTaskStepStatus.class, names = {"IN_PROGRESS", "COMPLETED", "BLOCKED", "CANCELED"})
    void shouldRequireNewStepsToStartPending(AgentTaskStepStatus status) {
        UpdateTaskPlanRequest request = request();
        UpdateTaskStepRequest added = copy(originals.getFirst());
        added.setStepId(null);
        added.setStatus(status);
        added.setResultSummary("说明");
        request.getSteps().add(added);
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        verify(repository, never()).insertStep(any());
    }

    // 用完整状态组合验证允许的路径，避免新增状态后意外放开所有变化。
    @ParameterizedTest
    @MethodSource("transitions")
    void shouldEnforceStateTransitions(AgentTaskStepStatus before, AgentTaskStepStatus after, boolean allowed) {
        AgentTaskStep original = originals.getFirst();
        original.setStatus(before);
        original.setResultSummary(needsReason(before) ? "已有结果" : null);
        UpdateTaskPlanRequest request = request();
        request.getSteps().getFirst().setStatus(after);
        request.getSteps().getFirst().setResultSummary(needsReason(after) ? "已有结果" : null);
        // 其他步骤有合法变化，同状态用例不会被“没有变化”的检查干扰。
        request.getSteps().getLast().setDescription("更新未执行步骤");
        if (allowed) {
            assertEquals(after, service.update(PLAN_ID, SESSION_ID, request).getSteps().getFirst().getStatus());
        } else {
            assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        }
    }

    // 已完成或取消的步骤不能改正文、完成条件或结果；需要重做时应新增步骤。
    @ParameterizedTest
    @EnumSource(value = AgentTaskStepStatus.class, names = {"COMPLETED", "CANCELED"})
    void shouldProtectFinishedStepContent(AgentTaskStepStatus status) {
        originals.getFirst().setStatus(status);
        originals.getFirst().setResultSummary("已有结果");
        for (int field = 0; field < 3; field++) {
            UpdateTaskPlanRequest request = request();
            UpdateTaskStepRequest first = request.getSteps().getFirst();
            if (field == 0) first.setDescription("改写历史内容");
            if (field == 1) first.setCompletionCriteria("降低完成条件");
            if (field == 2) first.setResultSummary("改写历史结果");
            assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        }
        verify(repository, never()).updateStep(any(), any(), any());
    }

    // 结束、受阻和取消必须说明结果或原因，不能只改一个状态标志。
    @ParameterizedTest
    @EnumSource(value = AgentTaskStepStatus.class, names = {"COMPLETED", "BLOCKED", "CANCELED"})
    void shouldRequireResultOrReason(AgentTaskStepStatus status) {
        originals.getFirst().setStatus(AgentTaskStepStatus.IN_PROGRESS);
        UpdateTaskPlanRequest request = request();
        request.getSteps().getFirst().setStatus(status);
        request.getSteps().getFirst().setResultSummary("  ");
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
    }

    // 步骤完整内容与结果都限长，待执行步骤不能提前声明执行结果。
    @Test
    void shouldRejectInvalidTextAndPrematureResults() {
        UpdateTaskPlanRequest request = request();
        UpdateTaskStepRequest first = request.getSteps().getFirst();
        first.setDescription(" ");
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        first.setDescription("字".repeat(AgentTaskPlanService.MAX_STEP_TEXT_LENGTH + 1));
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        first.setDescription("有效步骤");
        first.setCompletionCriteria(" ");
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        first.setCompletionCriteria("完成条件");
        first.setResultSummary("已经做好了");
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        first.setStatus(AgentTaskStepStatus.BLOCKED);
        first.setResultSummary("字".repeat(AgentTaskPlanService.MAX_RESULT_LENGTH + 1));
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        first.setStatus(null);
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        request.getSteps().set(0, null);
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        verify(repository, never()).parkStepPositions(any(), any(), any(), anyInt());
    }

    // 完全相同的计划不写步骤；版本在事务中的撤销由集成测试验证。
    @Test
    void shouldRejectNoopBeforeReordering() {
        assertThrows(ClientDataErrorException.class, () -> service.update(PLAN_ID, SESSION_ID, request()));
        verify(repository, never()).parkStepPositions(any(), any(), any(), anyInt());
    }

    // 数据库中的原顺序损坏时不能继续重排，防止覆盖未知数据。
    @Test
    void shouldRejectCorruptStoredOrder() {
        originals.getFirst().setPosition(10);
        assertThrows(IllegalStateException.class, () -> service.update(PLAN_ID, SESSION_ID, request()));
        verify(repository, never()).parkStepPositions(any(), any(), any(), anyInt());
    }

    // 原步骤数量和实际重排数量不一致时必须终止，不能接着保存不完整的新列表。
    @Test
    void shouldRejectUnexpectedAffectedRows() {
        UpdateTaskPlanRequest request = request();
        request.getSteps().getFirst().setStatus(AgentTaskStepStatus.IN_PROGRESS);
        when(repository.parkStepPositions(USER_ID, SESSION_ID, PLAN_ID, AgentTaskPlanService.MAX_STEPS)).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.update(PLAN_ID, SESSION_ID, request));
        verify(repository, never()).updateStep(any(), any(), any());
    }

    // 声明允许的状态边，不复用生产方法计算期望值。
    private static Stream<Arguments> transitions() {
        List<String> edges = List.of("PENDING:PENDING", "PENDING:IN_PROGRESS", "PENDING:BLOCKED", "PENDING:CANCELED",
                "IN_PROGRESS:IN_PROGRESS", "IN_PROGRESS:COMPLETED", "IN_PROGRESS:BLOCKED", "IN_PROGRESS:CANCELED",
                "BLOCKED:BLOCKED", "BLOCKED:IN_PROGRESS", "BLOCKED:CANCELED", "COMPLETED:COMPLETED", "CANCELED:CANCELED");
        return Arrays.stream(AgentTaskStepStatus.values()).flatMap(before -> Arrays.stream(AgentTaskStepStatus.values())
                .map(after -> Arguments.of(before, after, edges.contains(before + ":" + after))));
    }

    // 这些状态需要结果或原因，测试输入也遵循相同的数据要求。
    private static boolean needsReason(AgentTaskStepStatus status) {
        return status == AgentTaskStepStatus.COMPLETED || status == AgentTaskStepStatus.BLOCKED || status == AgentTaskStepStatus.CANCELED;
    }

    // 创建固定时间的原记录，便于检查更新没有改写创建时间。
    private AgentTaskStep storedStep(int position) {
        AgentTaskStep step = new AgentTaskStep();
        step.setStepId(UUID.randomUUID().toString());
        step.setPlanId(PLAN_ID);
        step.setPosition(position);
        step.setDescription("步骤" + position);
        step.setCompletionCriteria("完成条件" + position);
        step.setStatus(AgentTaskStepStatus.PENDING);
        step.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        step.setUpdatedAt(step.getCreatedAt());
        return step;
    }

    // 每次构造独立的完整更新请求，修改测试输入不能污染原数据库快照。
    private UpdateTaskPlanRequest request() {
        UpdateTaskPlanRequest request = new UpdateTaskPlanRequest();
        request.setExpectedVersion(1L);
        request.setSteps(new ArrayList<>(originals.stream().map(this::copy).toList()));
        return request;
    }

    // 更新 DTO 不携带用户、任务、目标或版本以外的计划元数据。
    private UpdateTaskStepRequest copy(AgentTaskStep step) {
        UpdateTaskStepRequest copy = new UpdateTaskStepRequest();
        copy.setStepId(step.getStepId());
        copy.setDescription(step.getDescription());
        copy.setCompletionCriteria(step.getCompletionCriteria());
        copy.setStatus(step.getStatus());
        copy.setResultSummary(step.getResultSummary());
        return copy;
    }
}
