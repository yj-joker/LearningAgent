package com.yjjoker.learningagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

// 按版本提交完整草案；完整列表能避免遗漏步骤或把旧内容拼接进新草案。
@Data
public class UpdateLearningPlanDraftRequest {
    // Agent 工具使用 draftRef；HTTP 路径已经提供引用时可以为空。
    private String draftRef;

    // 更新前读取到的草案版本。
    @NotNull(message = "草案版本不能为空")
    private Long expectedVersion;

    // 新标题；为空时由 Service 保留旧值。
    @Size(max = 200, message = "草案标题不能超过 200 个字符")
    private String title;

    // 新目标；为空时由 Service 保留旧值。
    @Size(max = 2000, message = "学习目标不能超过 2000 个字符")
    private String objective;

    // 新基础情况；为空时由 Service 保留旧值。
    @Size(max = 1000, message = "基础情况不能超过 1000 个字符")
    private String learnerProfile;

    // 新投入时间；为空时由 Service 保留旧值。
    @Size(max = 500, message = "每周投入不能超过 500 个字符")
    private String weeklyCommitment;

    // 新限制条件；为空时由 Service 保留旧值。
    @Size(max = 2000, message = "限制条件不能超过 2000 个字符")
    private String constraints;

    // 更新提交完整步骤列表，避免部分更新覆盖其他步骤。
    @Valid
    @NotEmpty(message = "草案至少需要一个步骤")
    @Size(max = 12, message = "草案最多包含 12 个步骤")
    private List<LearningPlanDraftStepRequest> steps;
}
