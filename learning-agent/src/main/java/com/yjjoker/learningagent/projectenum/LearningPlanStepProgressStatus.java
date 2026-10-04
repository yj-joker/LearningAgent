package com.yjjoker.learningagent.projectenum;

// 长期学习计划步骤的当前状态；模型建议不等于 CONFIRMED。
public enum LearningPlanStepProgressStatus {
    // 计划中有这一步，但用户还没有开始学习。
    NOT_STARTED,
    // 用户已经进入学习或练习阶段。
    IN_PROGRESS,
    // 用户已经审批通过基于证据的阶段完成申请。
    CONFIRMED
}
