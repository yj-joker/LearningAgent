package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskStepRequest;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 只负责创建和读取任务计划；暂不注册工具、不调用模型，也不改变 AgentLoop。
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentTaskPlanService {
    // 集中限制计划大小，防止把资料正文当作步骤写入；文本长度与建表定义保持一致。
    public static final int MAX_STEPS = 20;
    public static final int MAX_GOAL_LENGTH = 2000;
    public static final int MAX_CONSTRAINTS_LENGTH = 2000;
    public static final int MAX_STEP_TEXT_LENGTH = 1000;

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
    @Transactional(readOnly = true)
    public AgentTaskPlan load(String runId, Long sessionId) {
        Long userId = requireCurrentUser();
        String canonicalRunId = requireRunId(runId);
        requireSession(userId, sessionId, false);
        AgentTaskPlan plan = repository.findPlan(userId, sessionId, canonicalRunId)
                .orElseThrow(() -> new ClientDataErrorException("任务计划不存在或无权访问"));
        // 步骤查询再次包含归属条件，结果按数据库中的 position 排序。
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

    // 检查会话归属与状态，创建计划只允许正在进行中的会话。
    private void requireSession(Long userId, Long sessionId, boolean creating) {
        if (sessionId == null || sessionId <= 0) {
            throw new ClientDataErrorException("学习会话 ID 不合法");
        }
        LearningSession session = sessions.findSessionById(sessionId)
                .orElseThrow(() -> new LearningSessionStatusException("学习会话不存在或无权访问"));
        if (!userId.equals(session.getUserId())) {
            throw new LearningSessionStatusException("学习会话不存在或无权访问");
        }
        if (session.getStatus() == null || session.getStatus() == LearningSessionStatusEnum.CANCELED
                || (creating && session.getStatus() != LearningSessionStatusEnum.ACTIVE)) {
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
