package com.yjjoker.learningagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

// 保存草案中的一个学习阶段；stepRef 在修改时保持不变，便于模型准确引用。
@Data
public class LearningPlanDraftStep {
    // 模型和页面共同使用的稳定步骤引用。
    private String stepRef;
    // 所属草案引用。
    private String draftRef;
    // 当前展示和执行顺序。
    private int position;
    // 这个阶段要学习或完成的内容。
    private String description;
    // 判断阶段完成的可观察条件。
    private String completionCriteria;
    // 步骤首次创建时间。
    private LocalDateTime createdAt;
    // 步骤最近修改时间。
    private LocalDateTime updatedAt;
}
