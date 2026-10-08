package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.projectenum.CourseLearningStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

// 返回课程、当前知识点和全部知识点进度，作为课程模式的统一后端事实视图。
@Data
@NoArgsConstructor
public class CourseLearningProgressVO {
    private Long sessionId;
    private Long courseId;
    private String courseName;
    private LocalDateTime courseUpdatedAt;
    // 整体状态针对本次课程快照，不会修改聊天会话的生命周期。
    private CourseLearningStatus status;
    private Long currentChapterId;
    private Long currentKnowledgePointId;
    private boolean completed;
    private boolean courseContentChanged;
    private List<CourseLearningPointProgressVO> points;

    // 组装课程学习进度查询结果。
    public CourseLearningProgressVO(Long sessionId, Long courseId, String courseName,
                                    LocalDateTime courseUpdatedAt, CourseLearningStatus status,
                                    Long currentChapterId, Long currentKnowledgePointId,
                                    boolean completed, boolean courseContentChanged,
                                    List<CourseLearningPointProgressVO> points) {
        this.sessionId = sessionId;
        this.courseId = courseId;
        this.courseName = courseName;
        this.courseUpdatedAt = courseUpdatedAt;
        this.status = status;
        this.currentChapterId = currentChapterId;
        this.currentKnowledgePointId = currentKnowledgePointId;
        this.completed = completed;
        this.courseContentChanged = courseContentChanged;
        // 固定本轮视图的列表，防止调用方增删元素改变当前进度范围。
        this.points = List.copyOf(points);
    }
}
