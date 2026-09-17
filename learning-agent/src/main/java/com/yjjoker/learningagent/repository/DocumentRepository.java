package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.Documents;
import com.yjjoker.learningagent.entity.VerifyAndDocumentMessage;
import com.yjjoker.learningagent.projectenum.DocumentEnum;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface DocumentRepository {
    // 保存文档信息
    @Insert("insert into documents (kb_id, filename, object_name, upload_request_id, status, chunk_count, parse_error, file_size, mime_type, upload_user_id, created_at, updated_at) " +
            "VALUES (#{document.kbId}, #{document.filename}, #{document.objectName}, #{document.uploadRequestId}, #{document.status}, #{document.chunkCount}, #{document.parseError}, #{document.fileSize}, #{document.mimeType}, #{document.uploadUserId}, #{document.createdAt}, #{document.updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "document.id", keyColumn = "id")
    int save(@Param("document") Documents document);

    // 查询同一用户此前已经处理过的上传请求。
    @Select("SELECT * FROM documents WHERE upload_user_id = #{uploadUserId} " +
            "AND upload_request_id = #{uploadRequestId} AND deleted_at IS NULL")
    Documents getByUploadRequestId(@Param("uploadUserId") Long uploadUserId,
                                   @Param("uploadRequestId") String uploadRequestId);

    //仅允许处于待解析状态的文档进入解析中状态，避免同一文档被重复提交解析。
    @Update("UPDATE documents SET status = 'PARSING', chunk_count = 0, parse_error = NULL, " +
            "updated_at = #{updatedAt} WHERE id = #{documentId} AND deleted_at IS NULL " +
            "AND status = 'UPLOADED'")
    int markParsingIfUploaded(@Param("documentId") Long documentId,
                              @Param("updatedAt") LocalDateTime updatedAt);

    //更新文档解析结果，并同步维护解析状态、切片数量和失败原因。
    @Update("UPDATE documents SET status = #{status}, chunk_count = #{chunkCount}, " +
            "parse_error = #{parseError}, updated_at = #{updatedAt} " +
            "WHERE id = #{documentId} AND deleted_at IS NULL AND status = #{expectedStatus}")
    int updateParseResult(@Param("documentId") Long documentId,
                          @Param("status") DocumentEnum status,
                          @Param("chunkCount") Integer chunkCount,
                          @Param("parseError") String parseError,
                          @Param("updatedAt") LocalDateTime updatedAt,
                          @Param("expectedStatus") DocumentEnum expectedStatus);
    // 根据id获取文档信息
    @Select("select * from documents where id = #{documentId} and deleted_at is null")
    Documents getById(@Param("documentId") Long documentId);

    //下载文档时，根据文档id获取文档信息和验证信息
     VerifyAndDocumentMessage getVerifyAndDocumentMessage(@Param("documentId") Long documentId);

}
