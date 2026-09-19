package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.config.MinioProperties;
import com.yjjoker.learningagent.entity.DocumentTask;
import com.yjjoker.learningagent.entity.Documents;
import com.yjjoker.learningagent.projectenum.DocumentEnum;
import com.yjjoker.learningagent.projectenum.DocumentTaskStatus;
import com.yjjoker.learningagent.repository.DocumentRepository;
import com.yjjoker.learningagent.repository.DocumentTaskRepository;
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
// 文档解析服务的异步实现类。
public class DocumentsParseServiceImpl implements DocumentsParseService {
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final DocumentRepository documentRepository;
    private final DocumentTaskRepository documentTaskRepository;
    private final DocumentParsePersistenceService documentParsePersistenceService;

    @Async("documentParseExecutor")
    @Override
    // 根据任务 ID 执行 PDF 解析、切片、向量化和结果持久化。
    public void parseDocuments(Long taskId) {
        if (taskId == null || taskId <= 0) {
            log.warn("任务 ID 不合法，跳过异步解析，taskId={}", taskId);
            return;
        }
        // 异步线程重新查询最新任务，避免使用调度器提交时的旧实体快照。
        DocumentTask task = documentTaskRepository.getById(taskId);
        if (task == null) {
            log.warn("待处理任务不存在，taskId={}", taskId);
            return;
        }
        if (DocumentTaskStatus.RUNNING != task.getStatus()) {
            log.info("任务当前不处于运行中状态，跳过异步解析，taskId={}，status={}",
                    taskId, task.getStatus());
            return;
        }
        // 异步线程重新查询最新文档，避免使用调度器提交时的旧实体快照。
        Documents document = documentRepository.getById(task.getDocumentId());
        if (document == null) {
            log.warn("待解析文档不存在或已删除，taskId={}，documentId={}", taskId, task.getDocumentId());
            markFailure(taskId, task.getDocumentId(), new IllegalStateException("文档不存在或已删除"));
            return;
        }
        if (document.getStatus() == DocumentEnum.READY) {
            // 文档当前状态为 READY，更新修改时间
            documentTaskRepository.markSuccess(taskId, LocalDateTime.now());
            return;
        }
        if (document.getStatus() != DocumentEnum.UPLOADED
                && document.getStatus() != DocumentEnum.PARSING) {
            log.info("文档当前不允许开始解析，taskId={}，documentId={}，status={}",
                    taskId, task.getDocumentId(), document.getStatus());
            markFailure(taskId, task.getDocumentId(), new IllegalStateException("文档状态不允许解析"));
            return;
        }
        if (document.getStatus() == DocumentEnum.UPLOADED
                && documentRepository.markParsingIfUploaded(document.getId(), LocalDateTime.now()) != 1) {
            log.info("文档状态未能从 UPLOADED 迁移到 PARSING，taskId={}，documentId={}",
                    taskId, document.getId());
            markFailure(taskId, document.getId(), new IllegalStateException("文档状态迁移失败"));
            return;
        }
        try {
            // 从 MinIO 获取 PDF 文本内容。
            String text = readPdfText(document);
            if (text.isBlank()) {
                throw new IllegalArgumentException("PDF 中未提取到可解析文本");
            }
            // 保存文档切片、调用 Embedding，并写入 Milvus。
            int chunkCount = documentParsePersistenceService
                    .replaceChunksAndMarkReady(document.getId(), text);
            // 文档结果成功后再将任务标记为 SUCCESS。
            documentTaskRepository.markSuccess(taskId, LocalDateTime.now());
            log.info("文档解析完成，taskId={}，documentId={}，chunkCount={}",
                    taskId, document.getId(), chunkCount);
        } catch (Exception e) {
            log.error("文档解析失败，taskId={}，documentId={}", taskId, document.getId(), e);
            // 短事务失败会自动回滚并恢复旧切片，新写入的 Milvus 向量由持久化服务按本次 ID 补偿。
            // 这里不能再按 documentId 无条件删除，否则可能把事务回滚后恢复的旧切片误删。
            markFailure(taskId, document.getId(), e);
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

    // 使用 PDFBox 按页码顺序提取文档文本。
    private String extractPdfText(GetObjectResponse response) throws Exception {
        try (PDDocument pdfDocument = PDDocument.load(response)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(pdfDocument);
        }
    }

    // 根据失败次数把任务放回队列或标记为最终失败，并同步文档状态。
    private void markFailure(Long taskId, Long documentId, Exception exception) {
        String errorMessage = exception.getMessage();
        if (errorMessage == null || errorMessage.isBlank()) {
            errorMessage = "文档解析失败";
        }
        if (errorMessage.length() > 2000) {
            errorMessage = errorMessage.substring(0, 2000);
        }
        try {
            documentTaskRepository.markFailureOrRetry(taskId, errorMessage, LocalDateTime.now());
            DocumentTask latestTask = documentTaskRepository.getById(taskId);
            if (latestTask != null && DocumentTaskStatus.PENDING == latestTask.getStatus()) {
                documentRepository.resetToUploadedIfParsing(documentId, LocalDateTime.now());
            } else {
                documentRepository.updateParseResult(
                        documentId,
                        DocumentEnum.FAILED,
                        0,
                        errorMessage,
                        LocalDateTime.now(),
                        DocumentEnum.PARSING
                );
            }
        } catch (Exception updateException) {
            log.error("更新文档处理失败状态失败，taskId={}，documentId={}",
                    taskId, documentId, updateException);
        }
    }
}
