package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.OwnerType;
import com.yjjoker.learningagent.projectenum.VisibilityEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeBase {
    private Long id;
    private Long courseId;
    private String name;
    private String description;
    private OwnerType ownerType;
    private VisibilityEnum visibility;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
