package com.yjjoker.learningagent.vo;

import lombok.Getter;

// 向页面和模型返回的草案步骤，不包含数据库时间和用户主键。
@Getter
public class LearningPlanDraftStepVO {
    // 模型和页面引用步骤时使用的稳定编号。
    private final String stepRef;
    // 当前顺序和阶段内容。
    private final int position;
    private final String description;
    private final String completionCriteria;

    public LearningPlanDraftStepVO(String stepRef, int position, String description, String completionCriteria) {
        this.stepRef = stepRef;
        this.position = position;
        this.description = description;
        this.completionCriteria = completionCriteria;
    }
}
