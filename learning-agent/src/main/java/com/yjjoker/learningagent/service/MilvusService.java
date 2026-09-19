package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.entity.DocumentChunks;
import com.yjjoker.learningagent.entity.EmbeddingResult;

import java.util.List;

public interface MilvusService {

    int insertAll(List<DocumentChunks> documentChunks, List<EmbeddingResult> embeddingResults);

    // 按切片主键精确删除向量，用于新向量写入失败补偿和旧版本向量清理。
    int deleteByIds(List<Long> chunkIds);
}
