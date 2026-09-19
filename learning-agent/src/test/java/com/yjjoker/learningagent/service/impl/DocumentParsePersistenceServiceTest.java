package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.client.AliyunEmbeddingClient;
import com.yjjoker.learningagent.entity.DocumentChunks;
import com.yjjoker.learningagent.entity.EmbeddingResult;
import com.yjjoker.learningagent.repository.DocumentChunksRepository;
import com.yjjoker.learningagent.service.MilvusService;
import com.yjjoker.learningagent.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("文档解析持久化编排服务测试")
@ExtendWith(MockitoExtension.class)
class DocumentParsePersistenceServiceTest {

    @Mock
    private DocumentChunksRepository documentChunksRepository;

    @Mock
    private AliyunEmbeddingClient aliyunEmbeddingClient;

    @Mock
    private MilvusService milvusService;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private DocumentChunkTransactionService documentChunkTransactionService;

    @InjectMocks
    private DocumentParsePersistenceService documentParsePersistenceService;

    @Test
    @DisplayName("501 个字符应先写向量，再用稳定 ID 执行 MySQL 短事务")
    void shouldWriteVectorsBeforeReplacingDatabaseState() {
        when(documentChunksRepository.findIdsByDocumentId(1L)).thenReturn(List.of(11L, 12L));
        when(snowflakeIdGenerator.nextId()).thenReturn(101L, 102L);
        mockEmbeddingResults();

        int chunkCount = documentParsePersistenceService
                .replaceChunksAndMarkReady(1L, "a".repeat(501));

        ArgumentCaptor<List<DocumentChunks>> chunksCaptor = chunksCaptor();
        InOrder inOrder = inOrder(aliyunEmbeddingClient, milvusService, documentChunkTransactionService);
        inOrder.verify(aliyunEmbeddingClient).embedDocuments(anyList());
        inOrder.verify(milvusService).insertAll(anyList(), anyList());
        inOrder.verify(documentChunkTransactionService)
                .replaceChunksAndMarkReady(eq(1L), chunksCaptor.capture());
        inOrder.verify(milvusService).deleteByIds(List.of(11L, 12L));

        List<DocumentChunks> chunks = chunksCaptor.getValue();
        assertEquals(2, chunkCount);
        assertEquals(101L, chunks.get(0).getId());
        assertEquals(102L, chunks.get(1).getId());
        assertEquals(0, chunks.get(0).getChunkIndex());
        assertEquals(1, chunks.get(1).getChunkIndex());
        assertEquals(500, chunks.get(0).getContent().length());
        assertEquals(1, chunks.get(1).getContent().length());
    }

    @Test
    @DisplayName("MySQL 死锁后只重试短事务，不重复调用阿里云和 Milvus 插入")
    void shouldRetryOnlyDatabaseTransactionAfterLockFailure() {
        when(documentChunksRepository.findIdsByDocumentId(1L)).thenReturn(List.of(11L));
        when(snowflakeIdGenerator.nextId()).thenReturn(101L);
        mockEmbeddingResults();
        doThrow(new CannotAcquireLockException("模拟死锁"))
                .doNothing()
                .when(documentChunkTransactionService)
                .replaceChunksAndMarkReady(eq(1L), anyList());

        int chunkCount = documentParsePersistenceService
                .replaceChunksAndMarkReady(1L, "a".repeat(500));

        assertEquals(1, chunkCount);
        verify(documentChunkTransactionService, times(2))
                .replaceChunksAndMarkReady(eq(1L), anyList());
        verify(aliyunEmbeddingClient, times(1)).embedDocuments(anyList());
        verify(milvusService, times(1)).insertAll(anyList(), anyList());
        verify(milvusService, times(1)).deleteByIds(List.of(11L));
        verify(milvusService, never()).deleteByIds(List.of(101L));
    }

    @Test
    @DisplayName("MySQL 最终失败时只删除本次写入的新向量")
    void shouldCompensateOnlyNewVectorsWhenDatabaseFails() {
        when(documentChunksRepository.findIdsByDocumentId(1L)).thenReturn(List.of(11L));
        when(snowflakeIdGenerator.nextId()).thenReturn(101L);
        mockEmbeddingResults();
        doThrow(new IllegalStateException("模拟数据库写入失败"))
                .when(documentChunkTransactionService)
                .replaceChunksAndMarkReady(eq(1L), anyList());

        assertThrows(
                IllegalStateException.class,
                () -> documentParsePersistenceService
                        .replaceChunksAndMarkReady(1L, "a".repeat(500))
        );

        verify(milvusService).deleteByIds(List.of(101L));
        verify(milvusService, never()).deleteByIds(List.of(11L));
    }

    // 为每个输入文本生成同下标的模拟向量结果，测试只关注调用顺序而不依赖真实阿里云服务。
    private void mockEmbeddingResults() {
        when(aliyunEmbeddingClient.embedDocuments(anyList()))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(0);
                    return java.util.stream.IntStream.range(0, texts.size())
                            .mapToObj(index -> new EmbeddingResult(index, List.of()))
                            .toList();
                });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<List<DocumentChunks>> chunksCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
