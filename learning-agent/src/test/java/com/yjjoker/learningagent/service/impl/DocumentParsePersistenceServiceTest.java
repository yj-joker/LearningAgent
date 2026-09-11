package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.entity.DocumentChunks;
import com.yjjoker.learningagent.projectenum.DocumentEnum;
import com.yjjoker.learningagent.repository.DocumentChunksRepository;
import com.yjjoker.learningagent.repository.DocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("文档解析持久化服务测试")
@ExtendWith(MockitoExtension.class)
class DocumentParsePersistenceServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunksRepository documentChunksRepository;

    @InjectMocks
    private DocumentParsePersistenceService documentParsePersistenceService;

    @Test
    @DisplayName("501 个字符应保存两个连续序号的切片，并更新为 READY")
    void shouldSaveContinuousChunksAndMarkDocumentReady() {
        when(documentChunksRepository.saveChunk(anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        when(documentRepository.updateParseResult(
                eq(1L), eq(DocumentEnum.READY), eq(2), eq(null), any(LocalDateTime.class)
        )).thenReturn(1);

        int chunkCount = documentParsePersistenceService
                .replaceChunksAndMarkReady(1L, "a".repeat(501));

        ArgumentCaptor<List<DocumentChunks>> chunksCaptor = chunksCaptor();
        verify(documentChunksRepository).deleteChunkByDocumentId(1L);
        verify(documentChunksRepository).saveChunk(chunksCaptor.capture());
        List<DocumentChunks> chunks = chunksCaptor.getValue();
        assertEquals(2, chunkCount);
        assertEquals(0, chunks.get(0).getChunkIndex());
        assertEquals(1, chunks.get(1).getChunkIndex());
        assertEquals(500, chunks.get(0).getContent().length());
        assertEquals(1, chunks.get(1).getContent().length());
        verify(documentRepository).updateParseResult(
                eq(1L), eq(DocumentEnum.READY), eq(2), eq(null), any(LocalDateTime.class)
        );
    }

    @Test
    @DisplayName("批量切片写入不完整时不能更新文档为 READY")
    void shouldNotMarkDocumentReadyWhenChunkBatchIsIncomplete() {
        when(documentChunksRepository.saveChunk(anyList())).thenReturn(1);

        assertThrows(
                IllegalStateException.class,
                () -> documentParsePersistenceService
                        .replaceChunksAndMarkReady(1L, "a".repeat(501))
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<List<DocumentChunks>> chunksCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
