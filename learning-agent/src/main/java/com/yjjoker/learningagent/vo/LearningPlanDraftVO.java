package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.entity.LearningPlanDraft;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

// 对外展示一份草案；formal=false 是固定事实，防止把草案误认为已生效计划。
@Getter
public class LearningPlanDraftVO {
    // 模型和页面使用的草案稳定引用。
    private final String draftRef;
    // 草案标题和总体目标。
    private final String title;
    private final String objective;
    // 学习者基础、投入时间和限制条件。
    private final String learnerProfile;
    private final String weeklyCommitment;
    private final String constraints;
    private final String status;
    // 当前固定为 false，草案不会伪装成正式计划。
    private final boolean formal;
    // MANUAL 或 AGENT，只表示来源。
    private final String source;
    // 页面和 Agent 更新时使用的乐观锁版本。
    private final long version;
    // 展示时间，不参与模型权限判断。
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<LearningPlanDraftStepVO> steps;

    // 统一把数据库实体转换成页面和工具都能使用的公开视图。
    public LearningPlanDraftVO(LearningPlanDraft draft) {
        this.draftRef = draft.getDraftRef();
        this.title = draft.getTitle();
        this.objective = draft.getObjective();
        this.learnerProfile = draft.getLearnerProfile();
        this.weeklyCommitment = draft.getWeeklyCommitment();
        this.constraints = draft.getConstraints();
        this.status = draft.getStatus();
        this.formal = false;
        this.source = draft.getSource();
        this.version = draft.getVersion();
        this.createdAt = draft.getCreatedAt();
        this.updatedAt = draft.getUpdatedAt();
        this.steps = draft.getSteps().stream()
                .map(step -> new LearningPlanDraftStepVO(step.getStepRef(), step.getPosition(),
                        step.getDescription(), step.getCompletionCriteria()))
                .toList();
    }
}
