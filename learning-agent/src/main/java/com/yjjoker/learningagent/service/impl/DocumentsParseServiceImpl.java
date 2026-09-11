package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.config.MinioProperties;
import com.yjjoker.learningagent.entity.Documents;
import com.yjjoker.learningagent.projectenum.DocumentEnum;
import com.yjjoker.learningagent.repository.DocumentRepository;
import com.yjjoker.learningagent.service.DocumentsParseService;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@AllArgsConstructor
//文档解析服务的异步实现类
public class DocumentsParseServiceImpl implements DocumentsParseService {
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final DocumentRepository documentRepository;
    private final DocumentParsePersistenceService documentParsePersistenceService;

    @Async("documentParseExecutor")
    @Override
    public void parseDocuments(Long documentId) {
        if (documentId == null || documentId <= 0) {
            log.warn("文档 ID 不合法，跳过异步解析，documentId={}", documentId);
            return;
        }
        // 获取文档
        boolean parsingStarted = false;
        try {
            Documents document = documentRepository.getById(documentId);
            if (document == null) {
                log.warn("待解析文档不存在或已删除，documentId={}", documentId);
                return;
            }

            if (documentRepository.markParsingIfUploaded(documentId, LocalDateTime.now()) != 1) {
                log.info(
                        "文档状态迁移失败：仅允许 UPLOADED 状态进入 PARSING，"
                                + "文档可能已被处理、不存在或已删除，documentId={}",
                        documentId
                );
                return;
            }
            parsingStarted = true;
            // 从 MinIO 获取 PDF 文本内容
            String text = readPdfText(document);
            if (text.isBlank()) {
                throw new IllegalArgumentException("PDF 中未提取到可解析文本");
            }
            // 保存文档切片
            int chunkCount = documentParsePersistenceService
                    .replaceChunksAndMarkReady(documentId, text);
            log.info("文档解析完成，documentId={}, chunkCount={}", documentId, chunkCount);
        } catch (Exception e) {
            log.error("文档解析失败，documentId={}", documentId, e);
            if (parsingStarted) {
                // 清理失败文档的切片
                clearChunksQuietly(documentId);
                // 更新文档状态为失败
                updateFailedStatus(documentId, e);
            }
        }
    }
    // 从 MinIO 获取 PDF 对象，并使用 PDFBox 提取其中的文本。
    private String readPdfText(Documents document) throws Exception {
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .object(document.getObjectName())
                        .build()
        )) {
            return extractPdfText(response);
        } catch (MinioException e) {
            throw new IllegalStateException("从 MinIO 获取文档失败", e);
        }
    }
    // PDFBox 解析文档
    private String extractPdfText(GetObjectResponse response)
            throws Exception {
        try (PDDocument pdfDocument = PDDocument.load(response)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // 按页码顺序提取文本
            stripper.setSortByPosition(true);
            return stripper.getText(pdfDocument);
        }
    }
    // 解析失败后清理已经写入的部分切片，避免失败文档残留不完整数据。
    private void clearChunksQuietly(Long documentId) {
        try {
            documentParsePersistenceService.deleteChunks(documentId);
        } catch (Exception e) {
            log.error("清理失败文档的切片失败，documentId={}", documentId, e);
        }
    }
    //  将异步解析失败状态保存到数据库。
    private void updateFailedStatus(Long documentId, Exception exception) {
        String errorMessage = exception.getMessage();
        if (errorMessage == null || errorMessage.isBlank()) {
            errorMessage = "文档解析失败";
        }
        // 避免将过长异常信息直接写入数据库
        if (errorMessage.length() > 2000) {
            errorMessage = errorMessage.substring(0, 2000);
        }
        try {
            // 更新文档状态为失败
            documentRepository.updateParseResult(
                    documentId,
                    DocumentEnum.FAILED,
                    0,
                    errorMessage,
                    LocalDateTime.now()
            );
        } catch (Exception updateException) {
            log.error(
                    "更新文档失败状态失败，documentId={}",
                    documentId,
                    updateException
            );
        }
    }
}
