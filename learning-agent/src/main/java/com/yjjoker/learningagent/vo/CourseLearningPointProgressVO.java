package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.entity.CourseLearningPointProgress;
import com.yjjoker.learningagent.projectenum.LearningEvidenceType;
import com.yjjoker.learningagent.projectenum.CourseLearningPointStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

// 对外展示课程知识点进度，不暴露内部时间和数据库主键组合细节。
@Data
@NoArgsConstructor
public class CourseLearningPointProgressVO {
    private Long chapterId;
    private Long knowledgePointId;
    private Long chapterSortOrder;
    private Long knowledgePointSortOrder;
    private CourseLearningPointStatus status;
    private LearningEvidenceType evidenceType;
    private String evidenceSummary;
    private String assessmentReason;
    private String chapterTitle;
    private String knowledgePointName;
    private String knowledgePointDescription;
    private Long version;

    // 把数据库进度转换成页面和 Agent 可读取的知识点视图。
    public CourseLearningPointProgressVO(CourseLearningPointProgress progress) {
        this.chapterId = progress.getChapterId();
        this.knowledgePointId = progress.getKnowledgePointId();
        // 顺序来自初始化快照，不用数据库 ID 判断先学哪个知识点。
        this.chapterSortOrder = progress.getChapterSortOrderSnapshot();
        this.knowledgePointSortOrder = progress.getKnowledgePointSortOrderSnapshot();
        this.status = progress.getStatus();
        this.evidenceType = progress.getEvidenceType();
        this.evidenceSummary = progress.getEvidenceSummary();
        this.assessmentReason = progress.getAssessmentReason();
        this.chapterTitle = progress.getChapterTitleSnapshot();
        this.knowledgePointName = progress.getKnowledgePointNameSnapshot();
        this.knowledgePointDescription = progress.getKnowledgePointDescriptionSnapshot();
        this.version = progress.getVersion();
    }
}
