package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskStepRequest;
import com.yjjoker.learningagent.harness.plan.dto.UpdateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.UpdateTaskStepRequest;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.AgentTaskPlanRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

// 管理计划的创建、读取和安全更新；暂不注册工具、不调用模型，也不改变 AgentLoop。
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentTaskPlanService {
    // 集中限制计划大小，防止把资料正文当作步骤写入；文本长度与建表定义保持一致。
    public static final int MAX_STEPS = 20;
    public static final int MAX_GOAL_LENGTH = 2000;
    public static final int MAX_CONSTRAINTS_LENGTH = 2000;
    public static final int MAX_STEP_TEXT_LENGTH = 1000;
    public static final int MAX_RESULT_LENGTH = 2000;

    private final AgentTaskPlanRepository repository;
    private final LearningSessionRepository sessions;

    // 一次保存目标和全部步骤；runId 由 Harness 提供，不从模型输出中获取。
    @Transactional
    public AgentTaskPlan create(String runId, Long sessionId, CreateTaskPlanRequest request) {
        Long userId = requireCurrentUser();
        String canonicalRunId = requireRunId(runId);
        requireSession(userId, sessionId, true);
        // 先校验并复制所有输入，任何一步不合法时都不开始写数据库。
        AgentTaskPlan plan = preparePlan(canonicalRunId, userId, sessionId, request);
        String stage = "保存计划";
        try {
            // 不先查询“是否存在”，直接让数据库主键处理重复或并发创建。
            try {
                if (repository.insertPlan(plan) != 1) {
                    throw new IllegalStateException("任务计划保存数量不正确");
                }
            } catch (DuplicateKeyException exception) {
                throw new ClientDataErrorException("该任务已有计划，不能重复创建或覆盖");
            }
            stage = "保存步骤";
            for (AgentTaskStep step : plan.getSteps()) {
                if (repository.insertStep(step) != 1) {
                    throw new IllegalStateException("任务步骤保存数量不正确");
                }
            }
            // 方法返回后 Spring 才提交事务，所以这里不提前宣称提交成功。
            log.info("任务计划已写入，等待事务提交，runId={}，sessionId={}，version={}，stepCount={}",
                    canonicalRunId, sessionId, plan.getVersion(), plan.getSteps().size());
            return plan;
        } catch (RuntimeException exception) {
            // 保留原异常交给事务回滚；日志只记录阶段和异常类型，不输出 SQL 或任务正文。
            log.warn("任务计划保存失败，将回滚，runId={}，sessionId={}，stage={}，errorType={}",
                    canonicalRunId, sessionId, stage, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    // 按当前用户和会话读取完整计划；已完成会话可查看，已取消会话不可访问。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentTaskPlan load(String runId, Long sessionId) {
        Long userId = requireCurrentUser();
        String canonicalRunId = requireRunId(runId);
        requireSession(userId, sessionId, false);
        AgentTaskPlan plan = repository.findPlan(userId, sessionId, canonicalRunId)
                .orElseThrow(() -> new ClientDataErrorException("任务计划不存在或无权访问"));
        // 目标和步骤使用同一读取快照，避免刚读完版本就混入另一事务的新步骤。
        List<AgentTaskStep> steps = repository.findSteps(userId, sessionId, canonicalRunId);
        if (steps.isEmpty()) {
            log.error("任务计划缺少步骤，runId={}，sessionId={}", canonicalRunId, sessionId);
            throw new IllegalStateException("任务计划缺少步骤，请检查保存结果");
        }
        plan.setSteps(steps);
        log.info("任务计划读取完成，runId={}，sessionId={}，version={}，stepCount={}",
                canonicalRunId, sessionId, plan.getVersion(), steps.size());
        return plan;
    }

    // 读取当前任务已有计划；没有计划时返回 null，供专注模式首次进入时创建。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentTaskPlan loadIfPresent(String runId, Long sessionId) {
        Long userId = requireCurrentUser();
        String canonicalRunId = requireRunId(runId);
        requireSession(userId, sessionId, false);
        Optional<AgentTaskPlan> stored = repository.findPlan(userId, sessionId, canonicalRunId);
        if (stored.isEmpty()) {
            return null;
        }
        AgentTaskPlan plan = stored.get();
        List<AgentTaskStep> steps = repository.findSteps(userId, sessionId, canonicalRunId);
        if (steps.isEmpty()) {
            throw new IllegalStateException("任务计划缺少步骤，请检查保存结果");
        }
        plan.setSteps(steps);
        log.info("任务已有计划，复用当前计划，runId={}，sessionId={}，version={}，stepCount={}",
                canonicalRunId, sessionId, plan.getVersion(), steps.size());
        return plan;
    }

    // 按调用方读取的版本保存完整步骤列表；目标和用户限制不在这个更新入口中修改。
    @Transactional
    public AgentTaskPlan update(String runId, Long sessionId, UpdateTaskPlanRequest request) {
        Long userId = requireCurrentUser();
        String canonicalRunId = requireRunId(runId);
        requireSession(userId, sessionId, true);
        requireUpdateRequest(request);
        long expectedVersion = request.getExpectedVersion();
        LocalDateTime now = LocalDateTime.now();
        String stage = "检查计划版本";
        try {
            // 条件更新把“检查版本”和“取得修改权”合成一步；失败时不能自动换版本重试。
            if (repository.advanceVersion(userId, sessionId, canonicalRunId, expectedVersion, now) != 1) {
                log.warn("任务计划版本冲突或不可访问，runId={}，sessionId={}，expectedVersion={}，需要重新读取",
                        canonicalRunId, sessionId, expectedVersion);
                throw new ClientDataErrorException("任务计划已变化或不存在，请重新读取计划后再修改");
            }
            stage = "校验步骤变化";
            // 用当前读取得锁定后的数据，不使用本事务早期可能建立的旧读取快照。
            AgentTaskPlan plan = repository.findPlanForUpdate(userId, sessionId, canonicalRunId)
                    .orElseThrow(() -> new IllegalStateException("取得版本后未找到任务计划"));
            if (plan.getVersion() != expectedVersion + 1) {
                throw new IllegalStateException("取得的计划版本不一致");
            }
            Map<String, AgentTaskStep> originals = indexStoredSteps(canonicalRunId,
                    repository.findStepsForUpdate(userId, sessionId, canonicalRunId));
            List<AgentTaskStep> updated = prepareUpdatedSteps(canonicalRunId, originals, request.getSteps(), now);
            long changedCount = updated.stream().filter(step -> !sameStep(originals.get(step.getStepId()), step)).count();
            if (changedCount == 0) {
                // 抛出异常会一并撤销刚递增的版本，重复提交相同内容不制造新版本。
                throw new ClientDataErrorException("计划没有变化，无需提交更新");
            }
            stage = "调整步骤顺序";
            // 旧位置先暂存到 21～40，再逐步写回新位置；其他事务看不到中间顺序。
            if (repository.parkStepPositions(userId, sessionId, canonicalRunId, MAX_STEPS) != originals.size()) {
                throw new IllegalStateException("待调整的步骤数量不一致");
            }
            stage = "保存步骤变化";
            for (AgentTaskStep step : updated) {
                AgentTaskStep original = originals.get(step.getStepId());
                int affected = original == null ? repository.insertStep(step)
                        : repository.updateStep(userId, sessionId, step);
                if (affected != 1) {
                    throw new IllegalStateException("任务步骤更新数量不正确");
                }
                if (!sameStep(original, step)) {
                    // 只记录状态和顺序，不记录学习目标、资料正文或完成说明。
                    log.info("任务步骤已写入，等待事务提交，runId={}，stepId={}，position={}，beforeStatus={}，afterStatus={}",
                            canonicalRunId, step.getStepId(), step.getPosition(),
                            original == null ? "NEW" : original.getStatus(), step.getStatus());
                }
            }
            plan.setSteps(updated);
            log.info("任务计划已更新，等待事务提交，runId={}，sessionId={}，version={}，stepCount={}，changedCount={}",
                    canonicalRunId, sessionId, plan.getVersion(), updated.size(), changedCount);
            return plan;
        } catch (RuntimeException exception) {
            // 校验失败、重排失败和步骤写入失败都撤销版本变化，不留下半份新计划。
            log.warn("任务计划更新失败，将回滚，runId={}，sessionId={}，stage={}，errorType={}",
                    canonicalRunId, sessionId, stage, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    // 写库前检查请求大小和版本范围，不让空请求或版本溢出进入更新事务。
    private void requireUpdateRequest(UpdateTaskPlanRequest request) {
        if (request == null || request.getExpectedVersion() == null
                || request.getExpectedVersion() < 1 || request.getExpectedVersion() == Long.MAX_VALUE) {
            throw new ClientDataErrorException("必须提供有效的计划版本");
        }
        if (request.getSteps() == null || request.getSteps().isEmpty() || request.getSteps().size() > MAX_STEPS) {
            throw new ClientDataErrorException("计划步骤数量必须在 1 到 " + MAX_STEPS + " 之间，包含完成和取消的步骤");
        }
    }

    // 将已锁定的步骤按固定编号建立索引，并确认数据库中的旧顺序完整。
    private Map<String, AgentTaskStep> indexStoredSteps(String runId, List<AgentTaskStep> steps) {
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IllegalStateException("任务计划的原步骤数量不合法");
        }
        Map<String, AgentTaskStep> originals = new LinkedHashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            AgentTaskStep step = steps.get(index);
            if (step.getStepId() == null || step.getStatus() == null || !runId.equals(step.getRunId())
                    || step.getPosition() != index + 1 || originals.putIfAbsent(step.getStepId(), step) != null) {
                throw new IllegalStateException("任务计划的原步骤编号或顺序不完整");
            }
        }
        return originals;
    }

    // 生成新的完整步骤快照，旧步骤不得遗漏，新步骤只能从待执行开始。
    private List<AgentTaskStep> prepareUpdatedSteps(String runId, Map<String, AgentTaskStep> originals,
                                                   List<UpdateTaskStepRequest> requested, LocalDateTime now) {
        List<UpdateTaskStepRequest> inputs = new ArrayList<>(requested);
        List<AgentTaskStep> updated = new ArrayList<>();
        Set<String> included = new HashSet<>();
        int runningCount = 0;
        for (int index = 0; index < inputs.size(); index++) {
            UpdateTaskStepRequest input = inputs.get(index);
            if (input == null || input.getStatus() == null) {
                throw new ClientDataErrorException("步骤内容和状态不能为空");
            }
            AgentTaskStep original = null;
            if (input.getStepId() != null) {
                // 查找范围只有当前任务；伪造编号、其他任务编号和重复编号都不能通过。
                original = originals.get(input.getStepId());
                if (original == null || !included.add(input.getStepId())) {
                    throw new ClientDataErrorException("步骤编号不存在于当前计划或重复，请重新读取计划");
                }
            }
            AgentTaskStep step = new AgentTaskStep();
            step.setStepId(original == null ? UUID.randomUUID().toString() : original.getStepId());
            step.setRunId(runId);
            step.setPosition(index + 1);
            step.setDescription(requireText(input.getDescription(), MAX_STEP_TEXT_LENGTH, "步骤内容"));
            step.setCompletionCriteria(requireText(input.getCompletionCriteria(), MAX_STEP_TEXT_LENGTH, "完成条件"));
            step.setStatus(input.getStatus());
            step.setResultSummary(optionalText(input.getResultSummary(), MAX_RESULT_LENGTH, "步骤结果或原因"));
            requireStepChangeAllowed(original, step);
            step.setCreatedAt(original == null ? now : original.getCreatedAt());
            // 原样保留的步骤不改更新时间，临时重排产生的时间变化会被这个值还原。
            step.setUpdatedAt(sameStep(original, step) ? original.getUpdatedAt() : now);
            if (step.getStatus() == AgentTaskStepStatus.IN_PROGRESS) {
                runningCount++;
            }
            updated.add(step);
        }
        if (included.size() != originals.size()) {
            throw new ClientDataErrorException("更新必须保留全部原步骤，不需要的步骤请标记取消");
        }
        if (runningCount > 1) {
            throw new ClientDataErrorException("同一计划最多只能有一个执行中的步骤");
        }
        return updated;
    }

    // 检查状态流转和结果说明，已完成或取消的正文记录不能被改写。
    private void requireStepChangeAllowed(AgentTaskStep original, AgentTaskStep updated) {
        AgentTaskStepStatus status = updated.getStatus();
        if (original == null) {
            if (status != AgentTaskStepStatus.PENDING) {
                throw new ClientDataErrorException("新增步骤必须从待执行开始");
            }
        } else {
            boolean allowed = switch (original.getStatus()) {
                case PENDING -> status != AgentTaskStepStatus.COMPLETED;
                case IN_PROGRESS -> status != AgentTaskStepStatus.PENDING;
                case BLOCKED -> status == AgentTaskStepStatus.BLOCKED || status == AgentTaskStepStatus.IN_PROGRESS
                        || status == AgentTaskStepStatus.CANCELED;
                case COMPLETED, CANCELED -> status == original.getStatus();
            };
            if (!allowed) {
                throw new ClientDataErrorException("步骤状态不能从 " + original.getStatus() + " 变为 " + status);
            }
            if ((original.getStatus() == AgentTaskStepStatus.COMPLETED || original.getStatus() == AgentTaskStepStatus.CANCELED)
                    && (!Objects.equals(original.getDescription(), updated.getDescription())
                    || !Objects.equals(original.getCompletionCriteria(), updated.getCompletionCriteria())
                    || !Objects.equals(original.getResultSummary(), updated.getResultSummary()))) {
                throw new ClientDataErrorException("已完成或取消的步骤不能改写内容与结果，需要重做时请新增步骤");
            }
        }
        if ((status == AgentTaskStepStatus.COMPLETED || status == AgentTaskStepStatus.BLOCKED
                || status == AgentTaskStepStatus.CANCELED) && updated.getResultSummary() == null) {
            throw new ClientDataErrorException("完成、受阻或取消步骤时必须填写结果或原因");
        }
        if (status == AgentTaskStepStatus.PENDING && updated.getResultSummary() != null) {
            throw new ClientDataErrorException("待执行步骤不能填写执行结果");
        }
    }

    // 比较步骤的业务内容，不把数据库时间精度差异当成计划变化。
    private boolean sameStep(AgentTaskStep original, AgentTaskStep updated) {
        return original != null && original.getPosition() == updated.getPosition()
                && original.getStatus() == updated.getStatus()
                && Objects.equals(original.getDescription(), updated.getDescription())
                && Objects.equals(original.getCompletionCriteria(), updated.getCompletionCriteria())
                && Objects.equals(original.getResultSummary(), updated.getResultSummary());
    }

    // 生成后端拥有的计划对象，不允许请求指定归属、版本或步骤完成状态。
    private AgentTaskPlan preparePlan(String runId, Long userId, Long sessionId, CreateTaskPlanRequest request) {
        if (request == null) {
            throw new ClientDataErrorException("任务计划不能为空");
        }
        String goal = requireText(request.getGoal(), MAX_GOAL_LENGTH, "任务目标");
        String constraints = optionalText(request.getConstraints(), MAX_CONSTRAINTS_LENGTH, "任务限制");
        if (request.getSteps() == null || request.getSteps().isEmpty() || request.getSteps().size() > MAX_STEPS) {
            throw new ClientDataErrorException("计划步骤数量必须在 1 到 " + MAX_STEPS + " 之间");
        }
        // 固定列表顺序，创建独立步骤对象，避免保存时修改调用方的输入。
        List<CreateTaskStepRequest> inputs = new ArrayList<>(request.getSteps());
        LocalDateTime now = LocalDateTime.now();
        List<AgentTaskStep> steps = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            CreateTaskStepRequest input = inputs.get(index);
            if (input == null) {
                throw new ClientDataErrorException("第 " + (index + 1) + " 个步骤不能为空");
            }
            AgentTaskStep step = new AgentTaskStep();
            step.setStepId(UUID.randomUUID().toString());
            step.setRunId(runId);
            step.setPosition(index + 1);
            step.setDescription(requireText(input.getDescription(), MAX_STEP_TEXT_LENGTH, "步骤内容"));
            step.setCompletionCriteria(requireText(input.getCompletionCriteria(), MAX_STEP_TEXT_LENGTH, "完成条件"));
            step.setStatus(AgentTaskStepStatus.PENDING);
            step.setCreatedAt(now);
            step.setUpdatedAt(now);
            steps.add(step);
        }
        AgentTaskPlan plan = new AgentTaskPlan();
        plan.setRunId(runId);
        plan.setUserId(userId);
        plan.setSessionId(sessionId);
        plan.setGoal(goal);
        plan.setConstraints(constraints);
        plan.setVersion(1L);
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        plan.setSteps(steps);
        return plan;
    }

    // 从登录上下文获取用户，不接受请求正文提供的 userId。
    private Long requireCurrentUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) {
            throw new LearningSessionStatusException("当前用户未登录");
        }
        return userId;
    }

    // 检查会话归属与状态，创建和修改只允许正在进行中的会话。
    private void requireSession(Long userId, Long sessionId, boolean writing) {
        if (sessionId == null || sessionId <= 0) {
            throw new ClientDataErrorException("学习会话 ID 不合法");
        }
        LearningSession session = sessions.findSessionById(sessionId)
                .orElseThrow(() -> new LearningSessionStatusException("学习会话不存在或无权访问"));
        if (!userId.equals(session.getUserId())) {
            throw new LearningSessionStatusException("学习会话不存在或无权访问");
        }
        if (session.getStatus() == null || session.getStatus() == LearningSessionStatusEnum.CANCELED
                || (writing && session.getStatus() != LearningSessionStatusEnum.ACTIVE)) {
            throw new LearningSessionStatusException("学习会话状态不允许此操作");
        }
    }

    // 只接受完整 UUID，统一小写，避免同一任务因大小写不同变成两份计划。
    private String requireRunId(String runId) {
        if (runId == null) {
            throw new ClientDataErrorException("任务编号不合法");
        }
        try {
            String canonical = UUID.fromString(runId).toString();
            if (!canonical.equalsIgnoreCase(runId)) {
                throw new IllegalArgumentException("任务编号不是完整 UUID");
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw new ClientDataErrorException("任务编号不合法");
        }
    }

    // 必填文本先去掉首尾空白，再按 Unicode 字符数检查，兼容中文和表情。
    private String requireText(String text, int maxLength, String label) {
        String normalized = optionalText(text, maxLength, label);
        if (normalized == null) {
            throw new ClientDataErrorException(label + "不能为空");
        }
        return normalized;
    }

    // 可选限制为空时统一存 null；超长内容直接拒绝，不静默截断用户要求。
    private String optionalText(String text, int maxLength, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.strip();
        if (normalized.codePointCount(0, normalized.length()) > maxLength) {
            throw new ClientDataErrorException(label + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
}
