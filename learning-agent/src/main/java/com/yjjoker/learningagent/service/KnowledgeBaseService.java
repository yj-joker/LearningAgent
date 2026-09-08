package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.KnowledgeBaseDTO;
import com.yjjoker.learningagent.vo.KnowledgeBaseVO;

public interface KnowledgeBaseService {
    KnowledgeBaseVO addKnowledgeBase(KnowledgeBaseDTO knowledgeBaseDTO);
}
