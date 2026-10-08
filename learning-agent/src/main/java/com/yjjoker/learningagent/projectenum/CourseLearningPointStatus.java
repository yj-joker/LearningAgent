package com.yjjoker.learningagent.projectenum;

// 课程知识点的学习状态。
public enum CourseLearningPointStatus {
    NOT_STARTED,
    IN_PROGRESS,
    CONFIRMED,
    // 正文变化后保留旧证据，但需要重新学习和确认。
    REVIEW_REQUIRED,
    // 源课程已移除，保留历史记录，不参与课程推进。
    REMOVED
}
