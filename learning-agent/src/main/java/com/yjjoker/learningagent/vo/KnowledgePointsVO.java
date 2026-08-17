package com.yjjoker.learningagent.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgePointsVO {
    private Long id;
    private String name;
    private Long sortOrder;
    private String description;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
