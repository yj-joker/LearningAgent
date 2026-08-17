package com.yjjoker.learningagent.vo;

import com.yjjoker.learningagent.projectenum.KnowledgePointRelationTypeEnum;
import com.yjjoker.learningagent.projectenum.KnowledgePointRelationsSource;
import com.yjjoker.learningagent.projectenum.KnowledgePointRelationsStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgePointRelationsVO {
    private Long id;
    private Long fromPointId;
    private Long toPointId;
    private KnowledgePointRelationTypeEnum relationType;
    private KnowledgePointRelationsStatus status;
    private KnowledgePointRelationsSource source;
    private LocalDateTime createdAt;
}
