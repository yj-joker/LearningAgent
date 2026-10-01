package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.UpdateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.SessionFocusState;
import com.yjjoker.learningagent.harness.plan.model.SessionGoalSnapshot;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.AgentTaskPlanRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// 会话目标的持久化入口；所有修改都是短事务，事务内不调用模型或等待用户审批。
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionGoalService {
    // 当前先保留一个有界索引；超过上限明确拒绝，不静默丢弃旧目标。
    public static final int MAX_GOALS = 20;
    private final AgentTaskPlanRepository repository;
    private final AgentTaskPlanService plans;
    private final LearningSessionRepository sessions;

    // 每次新请求读取会话当前目标；没有目标时，Harness 才调用规划器。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionGoalSnapshot load(Long sessionId) {
        Long userId = requireAccess(sessionId);
        SessionFocusState state = repository.findFocus(userId, sessionId).orElse(null);
        return state == null || state.getActivePlanId() == null ? null : snapshot(state);
    }

    // 首次专注请求沿用已有行为：规划器生成短计划后，原子保存首个目标和当前指针。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SessionGoalSnapshot initialize(Long sessionId, CreateTaskPlanRequest request) {
        Long userId = requireAccess(sessionId);
        repository.ensureFocus(userId, sessionId);
        SessionFocusState state = repository.lockFocus(userId, sessionId)
                .orElseThrow(() -> new SecurityException("会话目标归属不一致"));
        // 另一个并发请求已经完成初始化时，不覆盖它刚保存的目标。
        if (state.getActivePlanId() != null) {
            log.info("复用并发创建的会话目标，sessionId={}，focusVersion={}", sessionId, state.getVersion());
            return snapshot(state);
        }
        return createAndSelect(state, request);
    }

    // 新目标写入和指针切换一起提交；原目标及其全部步骤都保留。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SessionGoalSnapshot create(SessionGoalSnapshot expected, CreateTaskPlanRequest request) {
        SessionFocusState state = lockExpected(expected, null);
        return createAndSelect(state, request);
    }

    // 切换到已有目标就是恢复原计划；不再调用规划器，也不重置步骤进度。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SessionGoalSnapshot switchTo(SessionGoalSnapshot expected, String goalRef) {
        AgentTaskPlan target = expected.resolve(goalRef);
        SessionFocusState state = lockExpected(expected, target);
        if (Objects.equals(state.getActivePlanId(), target.getPlanId())) {
            throw new ClientDataErrorException("该目标已经是当前目标，无需切换");
        }
        changePointer(state, target.getPlanId(), state.getNextGoalNumber());
        log.info("会话目标切换已写入，等待事务提交，sessionId={}，goalNumber={}，focusVersion={}",
                state.getSessionId(), target.getGoalNumber(), state.getVersion());
        return snapshot(state);
    }

    // 审批前确认目标版本和状态流转合法；这个入口绝不写库。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void validateProgress(SessionGoalSnapshot expected, UpdateTaskPlanRequest request) {
        requireUnchanged(expected);
        plans.validateUpdate(expected.getCurrentPlan(), request);
    }

    // 批准后锁住当前目标，再一次提交全部步骤变化；等待审批期间不持有锁。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SessionGoalSnapshot updateProgress(SessionGoalSnapshot expected, UpdateTaskPlanRequest request) {
        SessionFocusState state = lockExpected(expected, null);
        plans.update(state.getActivePlanId(), state.getSessionId(), request);
        // 计划版本已递增，返回新快照，供本轮继续执行和下一次暂停使用。
        SessionGoalSnapshot updated = snapshot(state);
        log.info("步骤进度已写入，等待事务提交，sessionId={}，planId={}，planVersion={}",
                state.getSessionId(), state.getActivePlanId(), updated.getCurrentPlan().getVersion());
        return updated;
    }

    // 审批前和恢复时检查同一份快照；过期批准不能被解释为对新目标的授权。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void requireUnchanged(SessionGoalSnapshot expected) {
        requireSnapshot(expected);
        Long sessionId = expected.getState().getSessionId();
        Long userId = requireAccess(sessionId);
        // 这里只比较指针和版本，不重复读取全部目标索引与步骤正文。
        SessionFocusState actual = repository.findFocus(userId, sessionId).orElseThrow(this::changed);
        compareState(expected.getState(), actual);
        AgentTaskPlan current = repository.findPlan(userId, sessionId, actual.getActivePlanId())
                .orElseThrow(this::changed);
        comparePlan(expected.getCurrentPlan(), current);
    }

    // 目标索引也带计划版本；审批等待期间目标的步骤变化后，旧切换方案必须重新确认。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void validateTarget(SessionGoalSnapshot expected, String goalRef) {
        requireUnchanged(expected);
        AgentTaskPlan target = expected.resolve(goalRef);
        AgentTaskPlan actual = repository.findPlan(expected.getState().getUserId(),
                expected.getState().getSessionId(), target.getPlanId()).orElseThrow(this::changed);
        comparePlan(target, actual);
        if (Objects.equals(target.getPlanId(), expected.getState().getActivePlanId())) {
            throw new ClientDataErrorException("该目标已经是当前目标，无需切换");
        }
    }

    // 新增前检查数量和完全重复的目标；语义是否相同由用户在审批中确认。
    private SessionGoalSnapshot createAndSelect(SessionFocusState state, CreateTaskPlanRequest request) {
        List<AgentTaskPlan> existing = repository.findGoals(state.getUserId(), state.getSessionId());
        if (existing.size() >= MAX_GOALS) {
            throw new ClientDataErrorException("当前会话最多保留 20 个目标，请另开会话");
        }
        if (request != null && request.getGoal() != null && existing.stream()
                .anyMatch(plan -> plan.getGoal().equals(request.getGoal().strip()))) {
            throw new ClientDataErrorException("同名目标已存在，请读取目标索引后切换");
        }
        // 计划 UUID 和执行 runId 独立；短引用只展示固定序号，不要求模型填写 UUID。
        AgentTaskPlan plan = plans.create(UUID.randomUUID().toString(), state.getSessionId(), request);
        int number = state.getNextGoalNumber();
        if (number < 1 || number == Integer.MAX_VALUE || repository.assignGoalNumber(
                state.getUserId(), state.getSessionId(), plan.getPlanId(), number) != 1) {
            throw new IllegalStateException("目标序号分配失败");
        }
        changePointer(state, plan.getPlanId(), number + 1);
        log.info("会话目标已写入，等待事务提交，sessionId={}，planId={}，goalNumber={}，stepCount={}",
                state.getSessionId(), plan.getPlanId(), number, plan.getSteps().size());
        return snapshot(state);
    }

    // 先锁会话指针，再按固定编号锁相关计划；检查成功到提交前不允许并发修改它们。
    private SessionFocusState lockExpected(SessionGoalSnapshot expected, AgentTaskPlan target) {
        requireSnapshot(expected);
        Long sessionId = expected.getState().getSessionId();
        Long userId = requireAccess(sessionId);
        SessionFocusState state = repository.lockFocus(userId, sessionId).orElseThrow(this::changed);
        compareState(expected.getState(), state);
        List<AgentTaskPlan> targets = new ArrayList<>();
        targets.add(expected.getCurrentPlan());
        if (target != null && !target.getPlanId().equals(expected.getCurrentPlan().getPlanId())) targets.add(target);
        targets.sort(java.util.Comparator.comparing(AgentTaskPlan::getPlanId));
        for (AgentTaskPlan original : targets) {
            AgentTaskPlan actual = repository.findPlanForUpdate(userId, sessionId, original.getPlanId())
                    .orElseThrow(this::changed);
            comparePlan(original, actual);
        }
        return state;
    }

    // 仅修改当前指针和会话版本；不会把旧目标的 IN_PROGRESS 步骤改成取消。
    private void changePointer(SessionFocusState state, String planId, int nextNumber) {
        if (state.getVersion() == Long.MAX_VALUE || repository.changeFocus(state.getUserId(), state.getSessionId(),
                planId, state.getVersion(), nextNumber) != 1) throw changed();
        state.setActivePlanId(planId);
        state.setVersion(state.getVersion() + 1);
        state.setNextGoalNumber(nextNumber);
    }

    // 目标索引和当前完整计划来自同一事务，避免把新目标与旧步骤拼在一起。
    private SessionGoalSnapshot snapshot(SessionFocusState state) {
        SessionGoalSnapshot snapshot = new SessionGoalSnapshot();
        snapshot.setState(state);
        snapshot.setGoals(repository.findGoals(state.getUserId(), state.getSessionId()));
        snapshot.setCurrentPlan(plans.load(state.getActivePlanId(), state.getSessionId()));
        if (snapshot.getCurrentPlan().getGoalNumber() == null || snapshot.getGoals().stream()
                .noneMatch(goal -> goal.getPlanId().equals(state.getActivePlanId()))) {
            throw new IllegalStateException("会话当前目标不在目标索引中");
        }
        return snapshot;
    }

    // 所有入口都校验登录归属和有效会话，不能只依赖 Controller 的一次检查。
    private Long requireAccess(Long sessionId) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || sessionId == null || sessionId <= 0) throw new SecurityException("缺少有效会话");
        var session = sessions.findSessionById(sessionId).orElseThrow(() -> new SecurityException("会话不可访问"));
        if (!Objects.equals(userId, session.getUserId()) || session.getStatus() != LearningSessionStatusEnum.ACTIVE) {
            throw new SecurityException("会话不可访问");
        }
        return userId;
    }

    // 检查点必须属于当前用户，并且目标确实属于该会话。
    private void requireSnapshot(SessionGoalSnapshot snapshot) {
        if (snapshot == null || snapshot.getState() == null || snapshot.getCurrentPlan() == null
                || !Objects.equals(snapshot.getState().getUserId(), BaseContext.getCurrentId())
                || !Objects.equals(snapshot.getCurrentPlan().getUserId(), snapshot.getState().getUserId())
                || !Objects.equals(snapshot.getCurrentPlan().getSessionId(), snapshot.getState().getSessionId())) {
            throw new SecurityException("目标快照缺失或归属不一致，请重新发起请求");
        }
    }

    // 会话版本能发现切走再切回；只比较目标 ID 会漏掉这种情况。
    private void compareState(SessionFocusState expected, SessionFocusState actual) {
        if (expected.getVersion() != actual.getVersion()
                || !Objects.equals(expected.getActivePlanId(), actual.getActivePlanId())) throw changed();
    }

    // 计划版本保护步骤内容，固定序号保护模型短引用的身份。
    private void comparePlan(AgentTaskPlan expected, AgentTaskPlan actual) {
        if (!Objects.equals(expected.getPlanId(), actual.getPlanId()) || expected.getVersion() != actual.getVersion()
                || !Objects.equals(expected.getGoalNumber(), actual.getGoalNumber())) throw changed();
    }

    // 只记录冲突类型，不输出目标正文；调用方重新读取并重新申请审批。
    private ClientDataErrorException changed() {
        log.warn("会话目标或计划版本已变化，拒绝旧快照写入");
        return new ClientDataErrorException("会话目标或计划已变化，请重新读取目标并申请审批");
    }
}
