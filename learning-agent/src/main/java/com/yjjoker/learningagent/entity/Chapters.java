package com.yjjoker.learningagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Chapters {
    private Long id;
    private Long courseId;
    private String title;
    private Long sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
