package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChaptersDTO {
    private Long id;
    @NotBlank(message = "章节标题不能为空")
    @Size(max = 255, message = "标题不能超过255个字符")
    private String title;
    @NotNull(message = "课程 ID 不能为空")
    @Positive(message = "课程 ID 必须大于 0")
    private Long courseId;
    @NotNull(message = "章节排序值不能为空")
    @PositiveOrZero(message = "章节排序值不能小于 0")
    @Max(value = 4_294_967_295L, message = "章节排序值超出数据库允许范围")
    private Long sortOrder;
}
