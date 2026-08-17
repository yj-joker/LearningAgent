package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.KnowledgePointRelationsDTO;
import com.yjjoker.learningagent.vo.KnowledgePointRelationsVO;

import java.util.List;

public interface KnowledgePointRelationsService {

    // 创建知识点关系并返回创建结果。
    KnowledgePointRelationsVO createRelations(KnowledgePointRelationsDTO relationDTO);

    // 根据关系 ID 列表批量删除知识点关系。
    void deleteRelationsByIds(List<Long> ids);
}
