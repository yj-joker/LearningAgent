package com.yjjoker.learningagent.harness.course.model;

import com.yjjoker.learningagent.projectenum.CourseLearningPointStatus;
import com.yjjoker.learningagent.projectenum.LearningEvidenceType;
import lombok.Data;

// 保存模型提交的课程进度建议；它不是数据库实体，批准前不会改变课程进度。
@Data
public class CourseProgressProposal {
    private String pointRef;
    private Long expectedVersion;
    private CourseLearningPointStatus targetStatus;
    private LearningEvidenceType evidenceType;
    private String evidenceSummary;
    private String assessmentReason;
    private String userEvidence;
}
