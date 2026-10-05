package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.entity.LearningPlanDraft;
import com.yjjoker.learningagent.entity.LearningPlanDraftStep;
import com.yjjoker.learningagent.entity.LearningPlanStepProgress;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.learningplan.dto.LearningProgressProposal;
import com.yjjoker.learningagent.repository.LearningPlanProgressRepository;
import com.yjjoker.learningagent.projectenum.LearningEvidenceType;
import com.yjjoker.learningagent.projectenum.LearningPlanStepProgressStatus;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.LearningPlanProgressVO;
import com.yjjoker.learningagent.vo.LearningPlanStepProgressVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 管理长期计划的进度快照；本阶段只读和初始化，不提供绕过审批的完成写入。
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningPlanProgressService {
    private final LearningPlanDraftService draftService;
    private final LearningPlanProgressRepository repository;

    // 读取当前用户的 ACTIVE 计划和步骤进度，返回给页面或后续教学流程。
    @Transactional
    public LearningPlanProgressVO loadCurrentUser(String draftRef) {
        Long userId = requireUser();
        LearningPlanDraft draft = draftService.findActiveForUser(userId, draftRef);
        ensureRows(userId, draft);
        List<LearningPlanStepProgress> stored = repository.findByDraft(userId, draft.getDraftRef());
        Map<String, LearningPlanStepProgress> byStepRef = new HashMap<>();
        for (LearningPlanStepProgress progress : stored) {
            byStepRef.put(progress.getStepRef(), progress);
        }
        List<LearningPlanStepProgressVO> steps = draft.getSteps().stream()
                .map(step -> new LearningPlanStepProgressVO(step,
                        byStepRef.getOrDefault(step.getStepRef(), defaultProgress(userId, draft, step))))
                .toList();
        log.info("读取长期学习计划进度，userId={}，draftRef={}，planVersion={}，semanticVersion={}，stepCount={}，confirmedCount={}",
                userId, draft.getDraftRef(), draft.getVersion(), draft.getSemanticVersion(), steps.size(),
                steps.stream().filter(step -> step.getStatus() == LearningPlanStepProgressStatus.CONFIRMED).count());
        return new LearningPlanProgressVO(draft.getDraftRef(), draft.getTitle(), draft.getVersion(),
                draft.getSemanticVersion(), steps);
    }

    // 预检时读取最新 ACTIVE 计划，确认申请目标属于当前用户和当前计划版本。
    @Transactional(readOnly = true)
    public void validateProposal(Long userId, LearningProgressProposal proposal) {
        LearningPlanDraft draft = draftService.findActiveForUser(userId, proposal.getDraftRef());
        if (draft.getVersion() != proposal.getPlanVersion()
                || draft.getVersion() != proposal.getEvaluatedPlanVersion()
                || draft.getSemanticVersion() != proposal.getEvaluatedSemanticVersion()) {
            throw new ClientDataErrorException("计划版本已变化，请重新读取当前学习计划进度");
        }
        boolean stepExists = draft.getSteps().stream()
                .anyMatch(step -> step.getStepRef().equals(proposal.getStepRef()));
        if (!stepExists) throw new ClientDataErrorException("步骤引用已失效，请重新读取学习计划进度");
        log.info("长期学习进度申请目标校验通过，userId={}，draftRef={}，stepRef={}，planVersion={}",
                userId, proposal.getDraftRef(), proposal.getStepRef(), proposal.getPlanVersion());
    }

    // 用户批准后在一个短事务内锁定计划和步骤进度，再用版本条件写入新证据。
    @Transactional
    public LearningPlanProgressVO applyApprovedProposal(Long userId, LearningProgressProposal proposal) {
        if (!java.util.Objects.equals(userId, com.yjjoker.learningagent.utils.BaseContext.getCurrentId())) {
            throw new ClientDataErrorException("当前用户身份已变化，请重新发起申请");
        }
        if (proposal == null || proposal.getTargetStatus() == null
                || (proposal.getTargetStatus() != LearningPlanStepProgressStatus.IN_PROGRESS
                && proposal.getTargetStatus() != LearningPlanStepProgressStatus.CONFIRMED)) {
            throw new ClientDataErrorException("长期进度只能申请 IN_PROGRESS 或 CONFIRMED");
        }
        if (proposal.getEvidenceType() == null
                || (proposal.getTargetStatus() == LearningPlanStepProgressStatus.CONFIRMED
                && proposal.getEvidenceType() != LearningEvidenceType.BOTH)) {
            throw new ClientDataErrorException("CONFIRMED 必须同时具备解释和独立练习证据");
        }
        if (proposal.getEvidenceSummary() == null || proposal.getEvidenceSummary().isBlank()
                || proposal.getAssessmentReason() == null || proposal.getAssessmentReason().isBlank()
                || proposal.getUserEvidence() == null || proposal.getUserEvidence().isBlank()) {
            throw new ClientDataErrorException("长期进度申请必须包含证据摘要、判断理由和用户原话");
        }
        LearningPlanDraft draft = draftService.findActiveForUserForUpdate(userId, proposal.getDraftRef());
        if (draft.getVersion() != proposal.getPlanVersion()
                || draft.getVersion() != proposal.getEvaluatedPlanVersion()
                || draft.getSemanticVersion() != proposal.getEvaluatedSemanticVersion()) {
            throw new ClientDataErrorException("计划在审批期间已经变化，请重新申请长期进度");
        }
        LearningPlanDraftStep step = draft.getSteps().stream()
                .filter(item -> item.getStepRef().equals(proposal.getStepRef())).findFirst()
                .orElseThrow(() -> new ClientDataErrorException("步骤已删除或不属于当前计划"));
        LearningPlanStepProgress current = repository.findForUpdate(userId, proposal.getDraftRef(), proposal.getStepRef());
        if (current == null) throw new ClientDataErrorException("步骤进度不存在，请重新读取学习计划");
        if (current.getVersion() != proposal.getExpectedProgressVersion()) {
            throw new ClientDataErrorException("步骤进度已经被其他请求修改，请重新读取");
        }
        if (current.getStatus() == LearningPlanStepProgressStatus.CONFIRMED) {
            throw new ClientDataErrorException("已确认步骤不能被旧申请覆盖");
        }
        if (proposal.getTargetStatus() == LearningPlanStepProgressStatus.IN_PROGRESS
                && current.getStatus() != LearningPlanStepProgressStatus.NOT_STARTED) {
            throw new ClientDataErrorException("只有未开始的步骤才能申请进入学习中");
        }
        if (proposal.getTargetStatus() == LearningPlanStepProgressStatus.CONFIRMED
                && current.getStatus() != LearningPlanStepProgressStatus.IN_PROGRESS) {
            throw new ClientDataErrorException("必须先将步骤标记为学习中，才能申请确认掌握");
        }
        LearningPlanStepProgress next = new LearningPlanStepProgress();
        next.setDraftRef(proposal.getDraftRef());
        next.setStepRef(step.getStepRef());
        next.setUserId(userId);
        next.setStatus(proposal.getTargetStatus());
        next.setEvidenceType(proposal.getEvidenceType());
        next.setEvidenceSummary(proposal.getEvidenceSummary());
        next.setAssessmentReason(proposal.getAssessmentReason());
        next.setEvaluatedPlanVersion(draft.getVersion());
        next.setEvaluatedSemanticVersion(draft.getSemanticVersion());
        next.setVersion(current.getVersion());
        next.setCreatedAt(current.getCreatedAt());
        next.setUpdatedAt(LocalDateTime.now());
        if (repository.updateIfVersionMatches(next, userId, proposal.getDraftRef(), proposal.getStepRef(),
                proposal.getExpectedProgressVersion(), next.getUpdatedAt()) != 1) {
            throw new ClientDataErrorException("步骤进度已变化，请重新读取最新进度后申请");
        }
        log.info("长期学习进度事务写入成功，userId={}，draftRef={}，stepRef={}，status={}，progressVersion={}->{}，stepPosition={}",
                userId, proposal.getDraftRef(), step.getStepRef(), proposal.getTargetStatus(),
                current.getVersion(), current.getVersion() + 1, step.getPosition());
        return loadCurrentUser(proposal.getDraftRef());
    }

    // 为新增步骤补充初始进度行；ON DUPLICATE KEY 不会覆盖已有学习状态。
    private void ensureRows(Long userId, LearningPlanDraft draft) {
        LocalDateTime now = LocalDateTime.now();
        for (LearningPlanDraftStep step : draft.getSteps()) {
            LearningPlanStepProgress progress = defaultProgress(userId, draft, step);
            repository.insertIfAbsent(progress);
        }
    }

    // 创建未开始的默认进度；当前版本用于后续审批时检查计划是否已经变化。
    private LearningPlanStepProgress defaultProgress(Long userId, LearningPlanDraft draft,
                                                     LearningPlanDraftStep step) {
        LearningPlanStepProgress progress = new LearningPlanStepProgress();
        progress.setDraftRef(draft.getDraftRef());
        progress.setStepRef(step.getStepRef());
        progress.setUserId(userId);
        progress.setStatus(LearningPlanStepProgressStatus.NOT_STARTED);
        progress.setEvaluatedPlanVersion(draft.getVersion());
        progress.setEvaluatedSemanticVersion(draft.getSemanticVersion());
        progress.setVersion(1);
        progress.setCreatedAt(LocalDateTime.now());
        progress.setUpdatedAt(progress.getCreatedAt());
        return progress;
    }

    // 所有进度查询都必须使用登录身份，不能相信请求中的 userId。
    private Long requireUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) {
            throw new ClientDataErrorException("请先登录");
        }
        return userId;
    }
}
