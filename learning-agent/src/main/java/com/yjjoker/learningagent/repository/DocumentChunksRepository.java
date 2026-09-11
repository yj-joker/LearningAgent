package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.DocumentChunks;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentChunksRepository {
    int saveChunk(@Param("documentChunksList") List<DocumentChunks> documentChunksList);
    @Delete("DELETE FROM document_chunks WHERE document_id = #{documentId}")
    int deleteChunkByDocumentId(@Param("documentId") Long documentId);
}
