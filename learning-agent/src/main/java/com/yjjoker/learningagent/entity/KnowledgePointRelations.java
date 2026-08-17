package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.KnowledgePointRelationTypeEnum;
import com.yjjoker.learningagent.projectenum.KnowledgePointRelationsSource;
import com.yjjoker.learningagent.projectenum.KnowledgePointRelationsStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgePointRelations {
    private Long id;
    private Long fromPointId;
    private Long toPointId;
    private KnowledgePointRelationTypeEnum relationType;
    private KnowledgePointRelationsSource source;
    private KnowledgePointRelationsStatus status;
    private Long createdBy;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
