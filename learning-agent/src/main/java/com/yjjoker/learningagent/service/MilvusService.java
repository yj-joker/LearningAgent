package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.entity.DocumentChunks;
import com.yjjoker.learningagent.entity.Documents;
import com.yjjoker.learningagent.entity.EmbeddingResult;

import java.util.List;

public interface MilvusService {

    int insertAll(List<DocumentChunks> documentChunks, List<EmbeddingResult> embeddingResults);
}
