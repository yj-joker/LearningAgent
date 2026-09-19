package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.DocumentChunks;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentChunksRepository {
    int saveChunk(@Param("documentChunksList") List<DocumentChunks> documentChunksList);

    // 查询替换前的切片 ID，供 MySQL 提交成功后精确清理旧的 Milvus 向量。
    @Select("SELECT id FROM document_chunks WHERE document_id = #{documentId} ORDER BY chunk_index")
    List<Long> findIdsByDocumentId(@Param("documentId") Long documentId);

    @Delete("DELETE FROM document_chunks WHERE document_id = #{documentId}")
    int deleteChunkByDocumentId(@Param("documentId") Long documentId);
}
