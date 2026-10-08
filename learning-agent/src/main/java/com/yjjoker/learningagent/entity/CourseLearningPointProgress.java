package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.LearningEvidenceType;
import com.yjjoker.learningagent.projectenum.CourseLearningPointStatus;
import lombok.Data;

import java.time.LocalDateTime;

// 保存一个学习会话对一个课程知识点的掌握事实和课程内容快照。
@Data
public class CourseLearningPointProgress {
    private Long sessionId;
    private Long courseId;
    private Long chapterId;
    private Long knowledgePointId;
    private Long chapterSortOrderSnapshot;
    private Long knowledgePointSortOrderSnapshot;
    private CourseLearningPointStatus status;
    // 解释、练习和两者兼具的证据类型沿用现有教学定义。
    private LearningEvidenceType evidenceType;
    private String evidenceSummary;
    private String assessmentReason;
    private LocalDateTime courseUpdatedAtSnapshot;
    private String chapterTitleSnapshot;
    private String knowledgePointNameSnapshot;
    private String knowledgePointDescriptionSnapshot;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
