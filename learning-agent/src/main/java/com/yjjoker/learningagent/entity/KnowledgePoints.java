package com.yjjoker.learningagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgePoints {
    private Long id;
    private Long courseId;
    private Long chapterId;
    private Long sortOrder;
    private String name;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
