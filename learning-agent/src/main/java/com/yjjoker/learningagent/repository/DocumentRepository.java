package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.Documents;
import com.yjjoker.learningagent.entity.VerifyAndDocumentMessage;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentRepository {
    // 保存文档信息
    @Insert("insert into documents (kb_id, filename, object_name, status, file_size, mime_type, upload_user_id, created_at, updated_at) " +
            "VALUES (#{document.kbId}, #{document.filename}, #{document.objectName}, #{document.status}, #{document.fileSize}, #{document.mimeType}, #{document.uploadUserId}, #{document.createdAt}, #{document.updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "document.id", keyColumn = "id")
    int save(@Param("document") Documents document);
    // 根据id获取文档信息
    @Select("select * from documents where id = #{documentId}")
    Documents getById(@Param("documentId") Long documentId);

    //下载文档时，根据文档id获取文档信息和验证信息
     VerifyAndDocumentMessage getVerifyAndDocumentMessage(@Param("documentId") Long documentId);

}
