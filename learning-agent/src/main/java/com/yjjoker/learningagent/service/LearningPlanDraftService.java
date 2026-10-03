package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.CreateLearningPlanDraftRequest;
import com.yjjoker.learningagent.dto.LearningPlanDraftStepRequest;
import com.yjjoker.learningagent.dto.UpdateLearningPlanDraftRequest;
import com.yjjoker.learningagent.entity.LearningPlanDraft;
import com.yjjoker.learningagent.entity.LearningPlanDraftStep;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.repository.LearningPlanDraftRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.LearningPlanDraftVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

// 学习计划草案的唯一业务入口；手动接口和 Agent 工具最终调用同一组事务方法。
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningPlanDraftService {
    private static final int MAX_STEPS = 12;
    private static final int MAX_TITLE = 200;
    private static final int MAX_OBJECTIVE = 2000;
    private static final int MAX_PROFILE = 1000;
    private static final int MAX_WEEKLY_COMMITMENT = 500;
    private static final int MAX_CONSTRAINTS = 2000;
    private static final int MAX_STEP_TEXT = 1000;

    private final LearningPlanDraftRepository repository;

    // 手动创建草案；用户身份只从登录上下文取得，客户端不能指定 userId。
    @Transactional
    public LearningPlanDraftVO createManual(CreateLearningPlanDraftRequest request) {
        return create(request, "MANUAL");
    }

    // Agent 创建草案；调用前已经通过通用审批，正式写入仍在这里完成。
    @Transactional
    public LearningPlanDraftVO createAgent(CreateLearningPlanDraftRequest request) {
        return create(request, "AGENT");
    }

    // 返回当前用户的草案列表；每份草案再加载自己的步骤。
    public List<LearningPlanDraftVO> listCurrentUser() {
        Long userId = requireUser();
        List<LearningPlanDraft> drafts = repository.findDrafts(userId);
        drafts.forEach(this::loadSteps);
        log.info("加载学习计划草案列表，userId={}，draftCount={}", userId, drafts.size());
        return drafts.stream().map(LearningPlanDraftVO::new).toList();
    }

    // 返回当前用户的一份草案；draftRef 不匹配或不属于当前用户时统一按不存在处理。
    public LearningPlanDraftVO getCurrentUser(String draftRef) {
        Long userId = requireUser();
        LearningPlanDraft draft = findDraft(userId, draftRef);
        loadSteps(draft);
        log.info("读取学习计划草案，userId={}，draftRef={}，version={}，stepCount={}",
                userId, draftRef, draft.getVersion(), draft.getSteps().size());
        return new LearningPlanDraftVO(draft);
    }

    // 手动更新草案；HTTP 路径中的 draftRef 是唯一目标，正文中的引用不能改变目标。
    @Transactional
    public LearningPlanDraftVO updateManual(String draftRef, UpdateLearningPlanDraftRequest request) {
        return update(draftRef, request, "MANUAL");
    }

    // Agent 更新草案；批准后用相同的版本和步骤校验，不能靠自然语言绕过写入。
    @Transactional
    public LearningPlanDraftVO updateAgent(String draftRef, UpdateLearningPlanDraftRequest request) {
        return update(draftRef, request, "AGENT");
    }

    // Agent 申请创建前只校验输入，不写数据库也不改变草案状态。
    public void validateAgentCreate(CreateLearningPlanDraftRequest request) {
        requireUser();
        validateCreateRequest(request);
        validateCreateFields(request);
    }

    // Agent 申请更新前读取当前版本和步骤，只校验目标，不写数据库。
    public void validateAgentUpdate(String draftRef, UpdateLearningPlanDraftRequest request) {
        Long userId = requireUser();
        validateUpdateRequest(draftRef, request);
        LearningPlanDraft current = repository.findDraft(userId, draftRef);
        if (current == null) throw new NotFountException("学习计划草案不存在");
        loadSteps(current);
        if (!Objects.equals(current.getVersion(), request.getExpectedVersion())) {
            throw new ClientDataErrorException("草案版本已变化，请先重新读取最新草案");
        }
        buildUpdatedSteps(current, request.getSteps(), LocalDateTime.now());
        validateUpdateFields(current, request);
    }

    // 创建主体和全部步骤放在一个事务中，任一步失败都会回滚整份草案。
    private LearningPlanDraftVO create(CreateLearningPlanDraftRequest request, String source) {
        Long userId = requireUser();
        validateCreateRequest(request);
        validateCreateFields(request);
        LocalDateTime now = LocalDateTime.now();
        LearningPlanDraft draft = new LearningPlanDraft();
        draft.setDraftRef(UUID.randomUUID().toString());
        draft.setUserId(userId);
        draft.setTitle(normalizeRequired(request.getTitle(), MAX_TITLE, "草案标题"));
        draft.setObjective(normalizeRequired(request.getObjective(), MAX_OBJECTIVE, "学习目标"));
        draft.setLearnerProfile(normalizeOptional(request.getLearnerProfile(), MAX_PROFILE, "基础情况"));
        draft.setWeeklyCommitment(normalizeOptional(request.getWeeklyCommitment(), MAX_WEEKLY_COMMITMENT, "每周投入"));
        draft.setConstraints(normalizeOptional(request.getConstraints(), MAX_CONSTRAINTS, "限制条件"));
        draft.setStatus("DRAFT");
        draft.setSource(source);
        draft.setVersion(1);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);

        int draftRows = repository.insertDraft(draft);
        requireOne(draftRows, "保存学习计划草案失败");
        draft.setSteps(buildNewSteps(draft.getDraftRef(), request.getSteps(), now));
        for (LearningPlanDraftStep step : draft.getSteps()) {
            requireOne(repository.insertStep(step), "保存学习计划草案步骤失败");
        }
        log.info("学习计划草案创建成功，userId={}，draftRef={}，source={}，stepCount={}，formal=false",
                userId, draft.getDraftRef(), source, draft.getSteps().size());
        return new LearningPlanDraftVO(draft);
    }

    // 更新先锁主体和步骤快照，再检查版本和 stepRef，最后替换完整步骤列表。
    private LearningPlanDraftVO update(String draftRef, UpdateLearningPlanDraftRequest request, String source) {
        Long userId = requireUser();
        validateUpdateRequest(draftRef, request);
        LearningPlanDraft current = repository.findDraftForUpdate(userId, draftRef);
        if (current == null) {
            throw new NotFountException("学习计划草案不存在");
        }
        loadSteps(current);
        if (!Objects.equals(current.getVersion(), request.getExpectedVersion())) {
            throw new ClientDataErrorException("草案版本已变化，请先重新读取最新草案");
        }

        LocalDateTime now = LocalDateTime.now();
        LearningPlanDraft next = copyForUpdate(current, request, now);
        List<LearningPlanDraftStep> steps = buildUpdatedSteps(current, request.getSteps(), now);
        requireOne(repository.updateDraft(next, userId, request.getExpectedVersion(), now),
                "草案版本已变化，请刷新后重试");
        // 删除和重插入都在当前事务内，数据库异常会让主体版本更新一起回滚。
        repository.deleteSteps(draftRef);
        for (LearningPlanDraftStep step : steps) {
            requireOne(repository.insertStep(step), "保存新草案步骤失败");
        }
        next.setVersion(current.getVersion() + 1);
        next.setSteps(steps);
        log.info("学习计划草案更新成功，userId={}，draftRef={}，version={}->{}，editorSource={}，stepCount={}，formal=false",
                userId, draftRef, current.getVersion(), next.getVersion(), source, steps.size());
        return new LearningPlanDraftVO(next);
    }

    // 创建步骤时总是由后端生成稳定引用，避免模型伪造数据库编号。
    private List<LearningPlanDraftStep> buildNewSteps(String draftRef, List<LearningPlanDraftStepRequest> requests,
                                                       LocalDateTime now) {
        validateStepCount(requests);
        return sequence(requests, request -> {
            LearningPlanDraftStep step = new LearningPlanDraftStep();
            step.setStepRef(UUID.randomUUID().toString());
            step.setDraftRef(draftRef);
            step.setDescription(normalizeRequired(request.getDescription(), MAX_STEP_TEXT, "步骤内容"));
            step.setCompletionCriteria(normalizeRequired(request.getCompletionCriteria(), MAX_STEP_TEXT, "完成条件"));
            step.setCreatedAt(now);
            step.setUpdatedAt(now);
            return step;
        });
    }

    // 更新只接受当前草案中的 stepRef；空引用表示新增步骤，旧步骤未出现在列表中表示移除。
    private List<LearningPlanDraftStep> buildUpdatedSteps(LearningPlanDraft current,
                                                            List<LearningPlanDraftStepRequest> requests,
                                                            LocalDateTime now) {
        validateStepCount(requests);
        Map<String, LearningPlanDraftStep> existing = new HashMap<>();
        for (LearningPlanDraftStep step : current.getSteps()) {
            existing.put(step.getStepRef(), step);
        }
        HashSet<String> usedRefs = new HashSet<>();
        return sequence(requests, request -> {
            String stepRef = normalizeOptional(request.getStepRef(), 64, "步骤引用");
            if (stepRef == null) {
                stepRef = UUID.randomUUID().toString();
            } else if (!usedRefs.add(stepRef) || !existing.containsKey(stepRef)) {
                throw new ClientDataErrorException("草案步骤引用无效或重复，请使用当前草案中的 stepRef");
            }
            LearningPlanDraftStep old = existing.get(stepRef);
            LearningPlanDraftStep step = new LearningPlanDraftStep();
            step.setStepRef(stepRef);
            step.setDraftRef(current.getDraftRef());
            step.setDescription(normalizeRequired(request.getDescription(), MAX_STEP_TEXT, "步骤内容"));
            step.setCompletionCriteria(normalizeRequired(request.getCompletionCriteria(), MAX_STEP_TEXT, "完成条件"));
            step.setCreatedAt(old == null ? now : old.getCreatedAt());
            step.setUpdatedAt(now);
            return step;
        });
    }

    // 给步骤按输入顺序编号；position 是顺序，不是步骤身份。
    private List<LearningPlanDraftStep> sequence(List<LearningPlanDraftStepRequest> requests,
                                                  Function<LearningPlanDraftStepRequest, LearningPlanDraftStep> mapper) {
        List<LearningPlanDraftStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            LearningPlanDraftStep step = mapper.apply(requests.get(i));
            step.setPosition(i + 1);
            steps.add(step);
        }
        return List.copyOf(steps);
    }

    // 读取步骤并附加到主体对象，保证页面和工具看到同一份完整草案。
    private void loadSteps(LearningPlanDraft draft) {
        draft.setSteps(repository.findSteps(draft.getDraftRef()));
    }

    // 复制主体字段并保留草案的创建来源和未生效状态。
    private LearningPlanDraft copyForUpdate(LearningPlanDraft current, UpdateLearningPlanDraftRequest request,
                                            LocalDateTime now) {
        LearningPlanDraft next = new LearningPlanDraft();
        next.setDraftRef(current.getDraftRef());
        next.setUserId(current.getUserId());
        next.setTitle(normalizeRequired(request.getTitle() == null ? current.getTitle() : request.getTitle(),
                MAX_TITLE, "草案标题"));
        next.setObjective(normalizeRequired(request.getObjective() == null ? current.getObjective() : request.getObjective(),
                MAX_OBJECTIVE, "学习目标"));
        next.setLearnerProfile(normalizeOptional(request.getLearnerProfile() == null ? current.getLearnerProfile() : request.getLearnerProfile(),
                MAX_PROFILE, "基础情况"));
        next.setWeeklyCommitment(normalizeOptional(request.getWeeklyCommitment() == null ? current.getWeeklyCommitment() : request.getWeeklyCommitment(),
                MAX_WEEKLY_COMMITMENT, "每周投入"));
        next.setConstraints(normalizeOptional(request.getConstraints() == null ? current.getConstraints() : request.getConstraints(),
                MAX_CONSTRAINTS, "限制条件"));
        next.setStatus("DRAFT");
        next.setSource(current.getSource());
        next.setVersion(current.getVersion());
        next.setCreatedAt(current.getCreatedAt());
        next.setUpdatedAt(now);
        return next;
    }

    // 按当前用户和稳定引用读取草案，隐藏“存在但属于别人”的差异。
    private LearningPlanDraft findDraft(Long userId, String draftRef) {
        String ref = normalizeRequired(draftRef, 64, "草案引用");
        LearningPlanDraft draft = repository.findDraft(userId, ref);
        if (draft == null) {
            throw new NotFountException("学习计划草案不存在");
        }
        return draft;
    }

    // 创建请求由 Controller 校验一次，Agent 工具仍必须在这里重复校验。
    private void validateCreateRequest(CreateLearningPlanDraftRequest request) {
        if (request == null) throw new ClientDataErrorException("草案内容不能为空");
        validateStepCount(request.getSteps());
    }

    // 校验创建主体和每个步骤的文本长度；HTTP 注解不能覆盖 Agent 工具输入。
    private void validateCreateFields(CreateLearningPlanDraftRequest request) {
        normalizeRequired(request.getTitle(), MAX_TITLE, "草案标题");
        normalizeRequired(request.getObjective(), MAX_OBJECTIVE, "学习目标");
        normalizeOptional(request.getLearnerProfile(), MAX_PROFILE, "基础情况");
        normalizeOptional(request.getWeeklyCommitment(), MAX_WEEKLY_COMMITMENT, "每周投入");
        normalizeOptional(request.getConstraints(), MAX_CONSTRAINTS, "限制条件");
        for (LearningPlanDraftStepRequest step : request.getSteps()) {
            if (step == null) throw new ClientDataErrorException("草案步骤不能为空");
            normalizeRequired(step.getDescription(), MAX_STEP_TEXT, "步骤内容");
            normalizeRequired(step.getCompletionCriteria(), MAX_STEP_TEXT, "完成条件");
        }
    }

    // 更新必须带版本和完整步骤列表，避免模型只改一部分后覆盖其他编辑。
    private void validateUpdateRequest(String draftRef, UpdateLearningPlanDraftRequest request) {
        normalizeRequired(draftRef, 64, "草案引用");
        if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 1) {
            throw new ClientDataErrorException("expectedVersion 必须是正整数");
        }
        validateStepCount(request.getSteps());
    }

    // 校验更新主体；为空字段在正式更新时表示保留旧值。
    private void validateUpdateFields(LearningPlanDraft current, UpdateLearningPlanDraftRequest request) {
        normalizeRequired(request.getTitle() == null ? current.getTitle() : request.getTitle(), MAX_TITLE, "草案标题");
        normalizeRequired(request.getObjective() == null ? current.getObjective() : request.getObjective(), MAX_OBJECTIVE, "学习目标");
        normalizeOptional(request.getLearnerProfile() == null ? current.getLearnerProfile() : request.getLearnerProfile(), MAX_PROFILE, "基础情况");
        normalizeOptional(request.getWeeklyCommitment() == null ? current.getWeeklyCommitment() : request.getWeeklyCommitment(), MAX_WEEKLY_COMMITMENT, "每周投入");
        normalizeOptional(request.getConstraints() == null ? current.getConstraints() : request.getConstraints(), MAX_CONSTRAINTS, "限制条件");
    }

    // 限制步骤数量，防止把长篇计划一次写进单份草案。
    private void validateStepCount(List<LearningPlanDraftStepRequest> steps) {
        if (steps == null || steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new ClientDataErrorException("草案步骤数量必须在 1 到 " + MAX_STEPS + " 之间");
        }
    }

    // 必填文本统一去除首尾空白并限制字符数。
    private String normalizeRequired(String value, int max, String field) {
        String normalized = normalizeOptional(value, max, field);
        if (normalized == null) throw new ClientDataErrorException(field + "不能为空");
        return normalized;
    }

    // 可选文本为空时保存为 null，避免空字符串和 null 表示同一状态却产生版本更新。
    private String normalizeOptional(String value, int max, String field) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.codePointCount(0, normalized.length()) > max) {
            throw new ClientDataErrorException(field + "不能超过 " + max + " 个字符");
        }
        return normalized;
    }

    // 所有写入都要求当前请求已经通过登录拦截器。
    private Long requireUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) throw new ClientDataErrorException("请先登录");
        return userId;
    }

    // 影响行数不符合预期时抛异常，让事务回滚而不是返回半份草案。
    private void requireOne(int affectedRows, String message) {
        if (affectedRows != 1) throw new LearningAgentServiceException(message);
    }

}
