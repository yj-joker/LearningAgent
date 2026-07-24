package com.yjjoker.learningagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LearningSessionDTO {

    @NotNull
    @Valid
    private Long courseId;
    @NotNull
    @Valid
    private Long userId;
}
