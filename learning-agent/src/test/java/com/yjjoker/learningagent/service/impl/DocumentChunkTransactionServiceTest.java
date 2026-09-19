package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.entity.DocumentChunks;
import com.yjjoker.learningagent.projectenum.DocumentEnum;
import com.yjjoker.learningagent.repository.DocumentChunksRepository;
import com.yjjoker.learningagent.repository.DocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("文档切片 MySQL 短事务服务测试")
@ExtendWith(MockitoExtension.class)
class DocumentChunkTransactionServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunksRepository documentChunksRepository;

    @InjectMocks
    private DocumentChunkTransactionService documentChunkTransactionService;

    @Test
    @DisplayName("应按删除、插入、更新 READY 的顺序完成短事务")
    void shouldReplaceChunksAndMarkReadyInOrder() {
        List<DocumentChunks> chunks = List.of(chunk(101L, 0), chunk(102L, 1));
        when(documentChunksRepository.saveChunk(anyList())).thenReturn(2);
        when(documentRepository.updateParseResult(
                eq(1L), eq(DocumentEnum.READY), eq(2), eq(null),
                any(LocalDateTime.class), eq(DocumentEnum.PARSING)
        )).thenReturn(1);

        documentChunkTransactionService.replaceChunksAndMarkReady(1L, chunks);

        InOrder inOrder = inOrder(documentChunksRepository, documentRepository);
        inOrder.verify(documentChunksRepository).deleteChunkByDocumentId(1L);
        inOrder.verify(documentChunksRepository).saveChunk(chunks);
        inOrder.verify(documentRepository).updateParseResult(
                eq(1L), eq(DocumentEnum.READY), eq(2), eq(null),
                any(LocalDateTime.class), eq(DocumentEnum.PARSING)
        );
    }

    @Test
    @DisplayName("批量插入数量不完整时不能更新文档为 READY")
    void shouldNotMarkReadyWhenChunkInsertIsIncomplete() {
        List<DocumentChunks> chunks = List.of(chunk(101L, 0), chunk(102L, 1));
        when(documentChunksRepository.saveChunk(anyList())).thenReturn(1);

        assertThrows(
                IllegalStateException.class,
                () -> documentChunkTransactionService.replaceChunksAndMarkReady(1L, chunks)
        );

        verify(documentRepository, never()).updateParseResult(
                any(), any(), any(), any(), any(), any()
        );
    }

    // 创建最小切片对象，测试只验证短事务中的数据库调用顺序和失败边界。
    private DocumentChunks chunk(Long id, int chunkIndex) {
        DocumentChunks chunk = new DocumentChunks();
        chunk.setId(id);
        chunk.setDocumentId(1L);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent("content-" + chunkIndex);
        return chunk;
    }
}
