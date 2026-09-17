package com.yjjoker.learningagent.service.impl;

import com.alibaba.fastjson.JSON;
import com.yjjoker.learningagent.client.AliyunEmbeddingClient;
import com.yjjoker.learningagent.entity.DocumentChunks;
import com.yjjoker.learningagent.entity.DocumentChunksMetadata;
import com.yjjoker.learningagent.entity.EmbeddingResult;
import com.yjjoker.learningagent.projectenum.DocumentEnum;
import com.yjjoker.learningagent.repository.DocumentChunksRepository;
import com.yjjoker.learningagent.repository.DocumentRepository;
import com.yjjoker.learningagent.service.MilvusService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


//负责将已经提取的文本持久化为文档切片，并在同一事务中更新文档解析结果。避免内部类调用导致事务管理失效
@Service
@AllArgsConstructor
public class DocumentParsePersistenceService {
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_BATCH_SIZE = 10;

    private final DocumentRepository documentRepository;
    private final DocumentChunksRepository documentChunksRepository;
    private final AliyunEmbeddingClient aliyunEmbeddingClient;
    private final MilvusService milvusService;

    //替换文档已有切片，保存新的切片，并将文档状态更新为解析完成。
    // 任一步骤失败时，事务会回滚，避免留下部分切片或错误的 READY 状态。
    @Transactional
    public int replaceChunksAndMarkReady(Long documentId, String text) {
        // 删除旧切片
        documentChunksRepository.deleteChunkByDocumentId(documentId);
        // 保存新切片
        int chunkCount = saveChunks(documentId, text);
        int updatedRows = documentRepository.updateParseResult(
                documentId,
                DocumentEnum.READY,
                chunkCount,
                null,
                LocalDateTime.now(),
                DocumentEnum.PARSING
        );
        if (updatedRows != 1) {
            throw new IllegalStateException("更新文档解析完成状态失败");
        }
        return chunkCount;
    }
    // 删除指定文档的所有切片，用于解析失败后的补偿清理。
    public void deleteChunks(Long documentId) {
        documentChunksRepository.deleteChunkByDocumentId(documentId);
    }
    // 按固定文本长度生成连续序号的切片，并按批次写入数据库。
    private int saveChunks(Long documentId, String text) {
        int chunkCount = 0;
        List<DocumentChunks> batch = new ArrayList<>(CHUNK_BATCH_SIZE);
        List<String> texts = new ArrayList<>();
        for (int start = 0; start < text.length(); start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            DocumentChunks chunk = new DocumentChunks();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(chunkCount);
            chunk.setContent(text.substring(start, end));
            texts.add(chunk.getContent());
            chunk.setCreatedAt(LocalDateTime.now());
            DocumentChunksMetadata documentChunksMetadata = new DocumentChunksMetadata();
            documentChunksMetadata.setDocumentId(documentId);
            documentChunksMetadata.setChunkIndex(chunkCount);
            String metadata = JSON.toJSONString(documentChunksMetadata);
            chunk.setMetadata(metadata);
            batch.add(chunk);
            chunkCount++;
            // 每批次满10个切片时保存一次
            if (batch.size() == CHUNK_BATCH_SIZE) {
                // 保存当前批次的切片
                saveChunkBatch(batch);
                //将切片向量化并保存到向量库当中
                embeddingAndSave(texts, batch);
                texts.clear();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            // 保存剩余的切片
            saveChunkBatch(batch);
            //将切片向量化并保存到向量库当中
            embeddingAndSave(texts, batch);
        }
        return chunkCount;
    }
    // 批量保存一组切片，并确认数据库实际写入数量完整。
    private void saveChunkBatch(List<DocumentChunks> chunks) {
        int savedRows = documentChunksRepository.saveChunk(chunks);
        if (savedRows != chunks.size()) {
            throw new IllegalStateException("保存文档切片数量不完整");
        }
    }
    //将切片向量化并保存到向量库当中
    private void embeddingAndSave(List<String> texts, List<DocumentChunks> chunks) {
        List<EmbeddingResult> embeddingResults = aliyunEmbeddingClient.embedDocuments(texts);
        milvusService.insertAll(chunks, embeddingResults);
    }
}
