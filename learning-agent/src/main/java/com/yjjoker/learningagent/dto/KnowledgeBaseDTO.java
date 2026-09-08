package com.yjjoker.learningagent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class KnowledgeBaseDTO {
    @NotNull
    @Positive(message = "课程错误")
    private Long courseId;
    @NotNull
    private String name;
    private String description;
}
