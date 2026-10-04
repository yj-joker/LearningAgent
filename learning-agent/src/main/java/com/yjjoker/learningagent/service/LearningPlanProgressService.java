package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.entity.LearningPlanDraft;
import com.yjjoker.learningagent.entity.LearningPlanDraftStep;
import com.yjjoker.learningagent.entity.LearningPlanStepProgress;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.repository.LearningPlanProgressRepository;
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
