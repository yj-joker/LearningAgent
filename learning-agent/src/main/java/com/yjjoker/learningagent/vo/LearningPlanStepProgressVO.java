package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.entity.LearningPlanDraftStep;
import com.yjjoker.learningagent.entity.LearningPlanStepProgress;
import com.yjjoker.learningagent.projectenum.LearningEvidenceType;
import com.yjjoker.learningagent.projectenum.LearningPlanStepProgressStatus;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

// 对外展示步骤内容和进度；不暴露用户 ID、数据库时间和内部实现字段。
@Getter
public class LearningPlanStepProgressVO {
    // 页面和模型使用的稳定步骤引用。
    private final String stepRef;
    private final int position;
    private final String description;
    private final String completionCriteria;
    // 当前状态和最近一次已记录的证据。
    private final LearningPlanStepProgressStatus status;
    private final LearningEvidenceType evidenceType;
    private final String evidenceSummary;
    private final String assessmentReason;
    // 证据对应的计划版本，审批时用于判断是否过期。
    private final long evaluatedPlanVersion;
    private final long evaluatedSemanticVersion;
    private final long progressVersion;

    // 合并计划步骤和进度记录，缺少记录时使用未开始的默认状态。
    public LearningPlanStepProgressVO(LearningPlanDraftStep step, LearningPlanStepProgress progress) {
        this(step.getStepRef(), step.getPosition(), step.getDescription(), step.getCompletionCriteria(),
                progress.getStatus(), progress.getEvidenceType(), progress.getEvidenceSummary(),
                progress.getAssessmentReason(), progress.getEvaluatedPlanVersion(),
                progress.getEvaluatedSemanticVersion(), progress.getVersion());
    }

    // 检查点恢复时按 JSON 字段重建，不依赖数据库实体或计划步骤对象。
    @JsonCreator
    public LearningPlanStepProgressVO(
            @JsonProperty("stepRef") String stepRef,
            @JsonProperty("position") int position,
            @JsonProperty("description") String description,
            @JsonProperty("completionCriteria") String completionCriteria,
            @JsonProperty("status") LearningPlanStepProgressStatus status,
            @JsonProperty("evidenceType") LearningEvidenceType evidenceType,
            @JsonProperty("evidenceSummary") String evidenceSummary,
            @JsonProperty("assessmentReason") String assessmentReason,
            @JsonProperty("evaluatedPlanVersion") long evaluatedPlanVersion,
            @JsonProperty("evaluatedSemanticVersion") long evaluatedSemanticVersion,
            @JsonProperty("progressVersion") long progressVersion) {
        this.stepRef = stepRef;
        this.position = position;
        this.description = description;
        this.completionCriteria = completionCriteria;
        this.status = status;
        this.evidenceType = evidenceType;
        this.evidenceSummary = evidenceSummary;
        this.assessmentReason = assessmentReason;
        this.evaluatedPlanVersion = evaluatedPlanVersion;
        this.evaluatedSemanticVersion = evaluatedSemanticVersion;
        this.progressVersion = progressVersion;
    }
}
