package com.yjjoker.learningagent.harness.plan.model;

// 说明短期任务与长期学习计划的关系。
public enum LearningPlanTaskScope {
    // 用户正在学习当前推荐阶段。
    CURRENT_STAGE,
    // 用户明确学习计划中的其他阶段。
    OTHER_STAGE,
    // 用户请求与当前长期计划无关的内容。
    OUT_OF_PLAN
}
