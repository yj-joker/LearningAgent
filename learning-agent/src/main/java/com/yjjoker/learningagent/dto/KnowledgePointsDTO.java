package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgePointsDTO {
    @Positive(message = "知识点 ID 必须大于 0")
    private Long id;

    @NotNull(message = "课程 ID 不能为空")
    @Positive(message = "课程 ID 必须大于 0")
    private Long courseId;

    @NotNull(message = "章节 ID 不能为空")
    @Positive(message = "章节 ID 必须大于 0")
    private Long chapterId;

    @NotBlank(message = "知识点名称不能为空")
    @Size(max = 255, message = "知识点名称不能超过 255 个字符")
    private String name;

    @NotNull(message = "知识点排序值不能为空")
    @PositiveOrZero(message = "知识点排序值不能小于 0")
    @Max(value = 4_294_967_295L, message = "知识点排序值超出数据库允许范围")
    private Long sortOrder;

    // MySQL TEXT 最多存储 65,535 字节；utf8mb4 中单个字符最多占 4 字节。
    @Size(max = 16_383, message = "知识点描述不能超过 16383 个字符")
    private String description;
}
