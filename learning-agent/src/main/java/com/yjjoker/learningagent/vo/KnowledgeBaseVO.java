package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.projectenum.OwnerType;
import com.yjjoker.learningagent.projectenum.VisibilityEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeBaseVO {
    private Long id;
    private String name;
    private String description;
    private OwnerType ownerType;
    private VisibilityEnum visibility;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
