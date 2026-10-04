package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.LearningEvidenceType;
import com.yjjoker.learningagent.projectenum.LearningPlanStepProgressStatus;
import lombok.Data;

import java.time.LocalDateTime;

// 保存长期学习计划某一步的进度和最近一次已记录的学习证据。
@Data
public class LearningPlanStepProgress {
    // 计划和步骤引用共同确定一条进度记录，不使用数据库自增 ID。
    private String draftRef;
    private String stepRef;
    // 所属用户；读取和后续写入都必须校验归属。
    private Long userId;
    // 当前进度状态；模型只能提出建议，审批通过后才能变为 CONFIRMED。
    private LearningPlanStepProgressStatus status = LearningPlanStepProgressStatus.NOT_STARTED;
    // 最近一次记录的证据类型；未开始或没有证据时为空。
    private LearningEvidenceType evidenceType;
    // 证据的简短摘要，不重复保存完整聊天正文。
    private String evidenceSummary;
    // 模型对证据的判断理由；它是说明，不是直接授权。
    private String assessmentReason;
    // 这条证据对应的计划数据库版本。
    private long evaluatedPlanVersion;
    // 这条证据对应的计划语义版本；计划实质变化后不能复用旧审批。
    private long evaluatedSemanticVersion;
    // 进度记录自己的乐观锁版本，供后续审批更新使用。
    private long version;
    // 进度记录创建时间。
    private LocalDateTime createdAt;
    // 进度记录最近更新时间。
    private LocalDateTime updatedAt;
}
