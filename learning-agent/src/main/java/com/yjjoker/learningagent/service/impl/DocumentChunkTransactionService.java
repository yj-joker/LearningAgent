package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.entity.DocumentChunks;
import com.yjjoker.learningagent.projectenum.DocumentEnum;
import com.yjjoker.learningagent.repository.DocumentChunksRepository;
import com.yjjoker.learningagent.repository.DocumentRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
public class DocumentChunkTransactionService {
    private static final int DATABASE_BATCH_SIZE = 10;

    private final DocumentRepository documentRepository;
    private final DocumentChunksRepository documentChunksRepository;

    // 在一个短事务中替换 MySQL 切片并把文档更新为 READY。
    // 这个方法只执行数据库操作，不能在这里调用阿里云、Milvus 或其他远程服务。
    // 任一步骤失败都会回滚删除、插入和状态更新，旧切片不会因为半途中断而丢失。
    @Transactional
    public void replaceChunksAndMarkReady(Long documentId, List<DocumentChunks> chunks) {
        if (documentId == null || chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("文档 ID 和待保存切片不能为空");
        }

        // 先删除旧切片；如果后续插入或状态更新失败，该删除会随事务一起回滚。
        documentChunksRepository.deleteChunkByDocumentId(documentId);

        // 分批写入是为了限制单条 INSERT 的长度，同时让所有批次仍处于同一个短事务中。
        for (int start = 0; start < chunks.size(); start += DATABASE_BATCH_SIZE) {
            int end = Math.min(start + DATABASE_BATCH_SIZE, chunks.size());
            List<DocumentChunks> batch = chunks.subList(start, end);
            // 保存当前批次并核对影响行数，避免部分写入后错误地把文档标记为 READY。
            saveChunkBatch(batch);
        }

        // 只有旧数据删除和全部新切片写入成功后，才把文档状态从 PARSING 更新为 READY。
        int updatedRows = documentRepository.updateParseResult(
                documentId,
                DocumentEnum.READY,
                chunks.size(),
                null,
                LocalDateTime.now(),
                DocumentEnum.PARSING
        );
        if (updatedRows != 1) {
            throw new IllegalStateException("更新文档解析完成状态失败");
        }
    }

    // 保存一个数据库批次并确认实际写入行数完整，异常会交给外层事务触发回滚。
    private void saveChunkBatch(List<DocumentChunks> chunks) {
        int savedRows = documentChunksRepository.saveChunk(chunks);
        if (savedRows != chunks.size()) {
            throw new IllegalStateException("保存文档切片数量不完整");
        }
    }
}
