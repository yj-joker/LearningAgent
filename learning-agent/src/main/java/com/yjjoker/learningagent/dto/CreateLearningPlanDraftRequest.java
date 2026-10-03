package com.yjjoker.learningagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

// 创建一份未生效的学习计划草案；用户和 Agent 共用这份结构。
@Data
public class CreateLearningPlanDraftRequest {
    // 新草案标题。
    @NotBlank(message = "草案标题不能为空")
    @Size(max = 200, message = "草案标题不能超过 200 个字符")
    private String title;

    // 新草案总体目标。
    @NotBlank(message = "学习目标不能为空")
    @Size(max = 2000, message = "学习目标不能超过 2000 个字符")
    private String objective;

    // 用户已有基础，可为空。
    @Size(max = 1000, message = "基础情况不能超过 1000 个字符")
    private String learnerProfile;

    // 每周可投入时间，可为空。
    @Size(max = 500, message = "每周投入不能超过 500 个字符")
    private String weeklyCommitment;

    // 学习限制，可为空。
    @Size(max = 2000, message = "限制条件不能超过 2000 个字符")
    private String constraints;

    // 创建时至少提供一个步骤。
    @Valid
    @NotEmpty(message = "草案至少需要一个步骤")
    @Size(max = 12, message = "草案最多包含 12 个步骤")
    private List<LearningPlanDraftStepRequest> steps;
}
