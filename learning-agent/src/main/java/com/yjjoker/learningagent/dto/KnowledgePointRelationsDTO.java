package com.yjjoker.learningagent.dto;

import com.yjjoker.learningagent.projectenum.KnowledgePointRelationTypeEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class KnowledgePointRelationsDTO {
    // 创建关系时可不传；更新关系时由 Service 业务逻辑校验为必填。
    @Positive(message = "知识点关系 ID 必须大于 0")
    private Long id;

    @NotNull(message = "起始知识点 ID 不能为空")
    @Positive(message = "起始知识点 ID 必须大于 0")
    private Long fromPointId;

    @NotNull(message = "目标知识点 ID 不能为空")
    @Positive(message = "目标知识点 ID 必须大于 0")
    private Long toPointId;

    @NotNull(message = "知识点关系类型不能为空")
    private KnowledgePointRelationTypeEnum relationType;
}
