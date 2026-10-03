package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

// 确认草案正式生效时提交的版本；草案引用由路径或 Agent 参数提供。
@Data
public class ActivateLearningPlanDraftRequest {
    // Agent 通过稳定引用指定要确认的草案；页面版本从路径传入，因此该字段可为空。
    private String draftRef;

    // 页面和 Agent 都必须基于最新版本确认，避免确认旧内容。
    @NotNull(message = "草案版本不能为空")
    @Positive(message = "草案版本必须大于 0")
    private Long expectedVersion;
}
