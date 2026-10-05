package com.yjjoker.learningagent.harness.learningplan.dto;

import com.yjjoker.learningagent.projectenum.LearningEvidenceType;
import com.yjjoker.learningagent.projectenum.LearningPlanStepProgressStatus;
import lombok.Data;

// 保存模型提出的一次长期学习进度申请；它不是数据库实体，也不代表申请已经批准。
@Data
public class LearningProgressProposal {
    // 计划和步骤都使用模型可见的稳定引用，后端仍会重新核对归属。
    private String draftRef;
    private String stepRef;
    // 申请基于哪个计划版本和语义版本，审批等待期间版本变化时申请失效。
    private long planVersion;
    private long evaluatedPlanVersion;
    private long evaluatedSemanticVersion;
    // 进度行自己的乐观锁版本，防止旧申请覆盖新证据。
    private long expectedProgressVersion;
    // 只能申请开始学习或建议确认，模型不能申请数据库不存在的状态。
    private LearningPlanStepProgressStatus targetStatus;
    // 解释和独立练习是当前掌握判断使用的两类证据。
    private LearningEvidenceType evidenceType;
    // 只保存简短摘要和判断理由，不把完整聊天正文复制进进度表。
    private String evidenceSummary;
    private String assessmentReason;
    // 必须原样引用本轮用户消息中的连续文本，后端会重新核对。
    private String userEvidence;
}
