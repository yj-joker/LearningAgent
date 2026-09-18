package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.config.MinioProperties;
import com.yjjoker.learningagent.constant.MinioRetryData;
import com.yjjoker.learningagent.entity.Documents;
import com.yjjoker.learningagent.entity.DocumentTask;
import com.yjjoker.learningagent.entity.UploadFileVerifyMessage;
import com.yjjoker.learningagent.entity.VerifyAndDocumentMessage;
import com.yjjoker.learningagent.exception.DataIllegalException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.NullException;
import com.yjjoker.learningagent.exception.ViolationOperationException;
import com.yjjoker.learningagent.projectenum.*;
import com.yjjoker.learningagent.repository.DocumentRepository;
import com.yjjoker.learningagent.repository.KnowledgeBaseRepository;
import com.yjjoker.learningagent.service.DocumentService;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.DocumentDownload;
import com.yjjoker.learningagent.vo.DocumentVO;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.net.ssl.SSLException;
import java.io.*;
import java.net.*;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@AllArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentTaskPersistenceService documentTaskPersistenceService;


    private static final int BUFFER_SIZE = 64 * 1024;

    // 上传文档并创建文档解析任务。
    @Override
    public DocumentVO uploadDocument(MultipartFile file, Long kbId, String uploadRequestId) {
        // 校验上传请求的用户、文件、知识库 ID 和幂等键。
        validateUploadRequest(file, kbId, uploadRequestId);
        Long uploadUserId = BaseContext.getCurrentId();

        // 查询知识库信息，并校验当前用户是否拥有上传权限。
        validateUploadPermission(kbId, uploadUserId);

        // 准备本次上传使用的对象名、文件名和 MIME 类型。
        String objectName = UUID.randomUUID().toString();
        String filename = resolveFilename(file);
        String contentType = resolveContentType(file);

        // 将文件上传到 MinIO，瞬时故障按照现有策略重试。
        uploadObjectWithRetry(file, objectName, contentType);

        // 根据已确定的上传信息创建文档记录。
        Documents document = buildDocument(
                file,
                kbId,
                uploadRequestId,
                uploadUserId,
                objectName,
                filename,
                contentType
        );

        // 创建等待调度器处理的文档解析任务。
        DocumentTask task = buildDocumentTask(uploadUserId);

        // 在同一事务中保存文档和任务，并处理幂等冲突与数据库异常。
        return saveDocumentAndTaskSafely(document, task, objectName, uploadRequestId, uploadUserId);
    }

    // 下载文档：先完成数据库和权限校验，再返回带有文件元数据的流式响应。
    @Override
    public DocumentDownload prepareDownload(Long documentId) {
        if (documentId == null || documentId <= 0) {
            log.error("文档ID不能为空");
            throw new NullException("文档ID不能为空");
        }
        VerifyAndDocumentMessage verifyAndDocumentMessage;
        try {
            verifyAndDocumentMessage =
                    documentRepository.getVerifyAndDocumentMessage(documentId);
        } catch (Exception e) {
            log.error("获取数据库文档信息失败，documentId={}", documentId, e);
            throw new LearningAgentServiceException("获取文档信息失败");
        }
        //对应的文档数据库数据不存在
        if (verifyAndDocumentMessage == null) {
            log.error("数据库文档信息不存在，documentId={}", documentId);
            throw new NullException("文档不存在");
        }
        if (BaseContext.isCurrentIdNull()) {
            throw new ViolationOperationException("非法操作");
        }
        // 私有知识库中的文档只允许上传者下载。
        if (VisibilityEnum.PRIVATE.equals(verifyAndDocumentMessage.getVisibilityEnum())
                && !verifyAndDocumentMessage.getUploadUserId().equals(BaseContext.getCurrentId())) {
            log.error("无权限下载文档，documentId={}", documentId);
            throw new ViolationOperationException("无权限下载文档");
        }
        String bucketName = minioProperties.getBucketName();
        String objectName = verifyAndDocumentMessage.getObjectName();
        if (objectName == null || objectName.isBlank()) {
            log.error("文档的MinIO对象名称为空，documentId={}", documentId);
            throw new LearningAgentServiceException("文档存储信息不完整");
        }
        try {
            // statObject 只检查对象及其元数据，不会下载整个文件。
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
        } catch (ErrorResponseException e) {
            // MinIO 文件不存在
            String errorCode = e.errorResponse() == null ? null : e.errorResponse().code();
            if ("NoSuchKey".equals(errorCode) // 文件不存在
                    || "NoSuchObject".equals(errorCode) // 对象不存在
                    || "NoSuchBucket".equals(errorCode)) { // 桶不存在
                log.error("MinIO文件不存在，documentId={}，objectName={}，errorCode={}",
                        documentId, objectName, errorCode, e);
                throw new NullException("文件不存在");
            }
            log.error("检查MinIO文件时被拒绝或服务端返回错误，documentId={}，objectName={}，errorCode={}",
                    documentId, objectName, errorCode, e);
            throw new LearningAgentServiceException("检查MinIO文件失败");
        } catch (MinioException e) {
            log.error("检查MinIO文件失败，documentId={}，objectName={}",
                    documentId, objectName, e);
            throw new LearningAgentServiceException("检查MinIO文件失败");
        }
        String filename = verifyAndDocumentMessage.getFilename();
        String mimeType = verifyAndDocumentMessage.getMimeType();
        return new DocumentDownload(
                filename,
                mimeType,
                outputStream -> streamObject(objectName, bucketName, outputStream)
        );
    }




    // 校验上传请求的基础参数，避免无效数据进入后续的存储流程。
    private void validateUploadRequest(MultipartFile file, Long kbId, String uploadRequestId) {
        if (BaseContext.isCurrentIdNull()) {
            throw new ViolationOperationException("非法操作");
        }
        if (file == null || file.isEmpty()) {
            log.error("文件不能为空");
            throw new NullException("文件不能为空");
        }
        if (kbId == null || kbId <= 0) {
            throw new NullException("知识库ID不合法");
        }
        if (uploadRequestId == null || uploadRequestId.isBlank() || uploadRequestId.length() > 64) {
            throw new DataIllegalException("上传请求标识不合法");
        }
    }

    // 查询知识库上传校验信息，并确认当前用户拥有上传权限。
    private void validateUploadPermission(Long kbId, Long uploadUserId) {
        UploadFileVerifyMessage uploadFileVerifyMessage;
        try {
            uploadFileVerifyMessage = knowledgeBaseRepository.getUploadFileVerifyMessage(kbId);
        } catch (DataAccessException e) {
            log.error("获取知识库上传校验信息失败，kbId={}", kbId, e);
            throw new LearningAgentServiceException("获取知识库信息失败");
        }
        if (uploadFileVerifyMessage == null) {
            log.error("知识库不存在");
            throw new NullException("知识库不存在");
        }
        // 课程拥有者只能维护自己处于可编辑状态的用户知识库；管理员可以维护系统知识库。
        boolean isCourseOwner = uploadUserId.equals(uploadFileVerifyMessage.getUserId())
                && uploadFileVerifyMessage.getOwnerType() == OwnerType.USER
                && uploadFileVerifyMessage.getCoursesTypeEnum() == CoursesTypeEnum.PRIVATE;
        boolean isAdminSystemKnowledgeBase = BaseContext.getCurrentRole() == UserRoleEnum.ADMIN
                && uploadFileVerifyMessage.getOwnerType() == OwnerType.SYSTEM;
        if (!isCourseOwner && !isAdminSystemKnowledgeBase) {
            log.error("无权限上传文件");
            throw new ViolationOperationException("无权限上传文件");
        }
    }

    // 读取原始文件名；客户端没有提供文件名时使用默认名称。
    private String resolveFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return "uploaded-file";
        }
        return filename;
    }

    // 读取文件 MIME 类型；无法识别时使用通用二进制类型。
    private String resolveContentType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        return contentType;
    }

    // 将文件上传到 MinIO；仅对可能恢复的瞬时故障执行有限次数重试。
    private void uploadObjectWithRetry(MultipartFile file, String objectName, String contentType) {
        boolean uploadSuccess = false;
        for (int attempt = 0; attempt < MinioRetryData.MINIO_MAX_RETRY; attempt++) {
            try (InputStream inputStream = file.getInputStream()) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(minioProperties.getBucketName())
                                .object(objectName)
                                .stream(inputStream, file.getSize(), (long) -1)
                                .contentType(contentType)
                                .build()
                );
                uploadSuccess = true;
                break;
            } catch (IOException | MinioException e) {
                if (!isRetryable(e)) {
                    log.error("文件上传失败", e);
                    removeUploadedObjectQuietly(objectName);
                    throw new LearningAgentServiceException("文件上传失败");
                }
                if (attempt + 1 < MinioRetryData.MINIO_MAX_RETRY) {
                    long delayMillis = 100L * (1L << attempt);
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException interruptedException) {
                        // sleep会清空中断状态，需要重新设置，否则程序会认为没有被中断，导致无法捕获中断异常
                        Thread.currentThread().interrupt();
                        removeUploadedObjectQuietly(objectName);
                        log.error("文件上传重试过程被中断：{}", interruptedException.getMessage());
                        throw new LearningAgentServiceException("文件上传重试过程被中断");
                    }
                }
            }
        }
        if (!uploadSuccess) {
            log.error("文件上传失败，已达到最大重试次数");
            removeUploadedObjectQuietly(objectName);
            throw new LearningAgentServiceException("文件上传失败");
        }
    }

    // 根据上传信息创建待保存的文档实体。
    private Documents buildDocument(MultipartFile file,
                                    Long kbId,
                                    String uploadRequestId,
                                    Long uploadUserId,
                                    String objectName,
                                    String filename,
                                    String contentType) {
        Documents document = new Documents();
        document.setKbId(kbId);
        document.setFilename(filename);
        document.setObjectName(objectName);
        document.setUploadRequestId(uploadRequestId);
        document.setFileSize(file.getSize());
        document.setMimeType(contentType);
        document.setUploadUserId(uploadUserId);
        document.setStatus(DocumentEnum.UPLOADED);
        document.setChunkCount(0);
        document.setParseError(null);
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        return document;
    }

    // 创建与新文档对应的待处理向量化任务。
    private DocumentTask buildDocumentTask(Long uploadUserId) {
        DocumentTask task = new DocumentTask();
        task.setUserId(uploadUserId);
        task.setTaskType("VECTORIZE");
        task.setStatus(DocumentTaskStatus.PENDING);
        task.setMaxRetries(3);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    // 保存文档和解析任务，并把数据库异常转换为稳定的业务响应。
    private DocumentVO saveDocumentAndTaskSafely(Documents document,
                                                  DocumentTask task,
                                                  String objectName,
                                                  String uploadRequestId,
                                                  Long uploadUserId) {
        try {
            // 原子保存文档和任务，避免只保存其中一条记录。
            documentTaskPersistenceService.saveDocumentAndTask(document, task);
        } catch (DuplicateKeyException e) {
            removeUploadedObjectQuietly(objectName);
            // 幂等键冲突时查询第一次请求创建的文档，并返回同一结果。
            Documents existingDocument = getExistingDocument(uploadUserId, uploadRequestId);
            if (existingDocument == null) {
                log.error("重复上传请求已被拦截，但查询原文档失败，uploadRequestId={}", uploadRequestId, e);
                throw new LearningAgentServiceException("查询重复上传文档失败");
            }
            log.info("重复上传请求返回原文档，documentId={}，uploadRequestId={}",
                    existingDocument.getId(), uploadRequestId);
            return toDocumentVO(existingDocument);
        } catch (DataIntegrityViolationException e) {
            removeUploadedObjectQuietly(objectName);
            log.error("保存文档时违反数据库约束，filename={}", document.getFilename(), e);
            throw new DataIllegalException("文档数据不符合数据库约束");
        } catch (DataAccessException | IllegalStateException e) {
            removeUploadedObjectQuietly(objectName);
            log.error("保存文档数据库记录失败，已尝试清理 MinIO 对象，objectName={}", objectName, e);
            throw new LearningAgentServiceException("保存文档失败");
        } catch (RuntimeException e) {
            removeUploadedObjectQuietly(objectName);
            log.error("保存文档时发生未预期错误，已尝试清理 MinIO 对象，objectName={}", objectName, e);
            throw new LearningAgentServiceException("保存文档失败");
        }
        log.info("文档上传成功，处理任务已进入待调度状态，documentId={}，taskId={}",
                document.getId(), task.getId());
        return toDocumentVO(document);
    }

    // 根据用户和幂等键查询第一次上传创建的文档。
    private Documents getExistingDocument(Long uploadUserId, String uploadRequestId) {
        try {
            return documentRepository.getByUploadRequestId(uploadUserId, uploadRequestId);
        } catch (DataAccessException e) {
            log.error("查询重复上传对应的原文档失败，uploadRequestId={}", uploadRequestId, e);
            throw new LearningAgentServiceException("查询重复上传文档失败");
        }
    }

    // 将文档实体转换为上传接口返回对象。
    private DocumentVO toDocumentVO(Documents document) {
        DocumentVO documentVO = new DocumentVO();
        BeanUtils.copyProperties(document, documentVO);
        return documentVO;
    }


    //将 MinIO 对象按缓冲区写入 HTTP 输出流，不关闭由 Spring 管理的输出流
    private void streamObject(String objectName, String bucketName, OutputStream outputStream) throws IOException {
        try (GetObjectResponse minioInputStream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucketName).object(objectName).build())) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int readLength;
            while ((readLength = minioInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, readLength);
            }
            outputStream.flush();
        } catch (MinioException e) {
            log.error("从MinIO下载文件失败，objectName={}", objectName, e);
            throw new IOException("从MinIO下载文件失败", e);
        }
    }

    // 数据库保存失败或上传失败时清理已生成的 MinIO 对象，避免遗留孤儿文件
    private void removeUploadedObjectQuietly(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectName)
                    .build());
        } catch (Exception cleanupException) {
            log.error("清理 MinIO 孤儿对象失败，objectName={}", objectName, cleanupException);
        }
    }

    /**
     * 判断某个异常是否值得重试。
     * 返回 true ：当前异常可能是暂时性故障，可以在退避等待后重新执行上传。
     * 返回 false ：当前异常更像是权限、参数、文件、配置、证书、 线程取消或其他不会通过等待自动恢复的问题
     */
    private boolean isRetryable(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        //线程在睡眠的时候被中断，不重试
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        // 判断 MinIO 返回的明确错误响应找到ErrorResponseException异常
        ErrorResponseException errorResponseException =
                findCause(throwable, ErrorResponseException.class);

        if (errorResponseException != null) {
             //获取 S3/MinIO 错误码，例如：
            String errorCode = null;
            if (errorResponseException.errorResponse() != null) {
                errorCode = errorResponseException
                        .errorResponse()
                        .code();
            }
            // 判断错误码是否在非重试列表中，明确的不重试错误优先判断。
            if (errorCode != null
                    && MinioRetryData.NON_RETRYABLE_S3_CODES.contains(errorCode)) {
                return false;
            }
            // 判断错误码是否在可重试列表中
            if (errorCode != null
                    && MinioRetryData.RETRYABLE_S3_CODES.contains(errorCode)) {
                return true;
            }
            // 错误码没有识别出来，再退回到 HTTP 状态码判断。
            Response response;
            try {
                response = errorResponseException.response();
            } catch (Exception e) {
                log.warn("无法读取 MinIO 错误响应，不再重试当前上传", e);
                return false;
            }
            if (response != null) {
                int statusCode = response.code();
                return MinioRetryData.RETRYABLE_HTTP_STATUS.contains(statusCode);
            }
            return false;
        }
        /*
         * 判断本地数据源是否有问题。
         * 这些异常说明上传所依赖的输入文件或输入流本身有问题，
         * 重新执行同样的读取动作通常没有意义。
         */
        if (hasCause(throwable, FileNotFoundException.class)
                || hasCause(throwable, NoSuchFileException.class)
                || hasCause(throwable, AccessDeniedException.class)
                || hasCause(throwable, EOFException.class)
                || hasCause(throwable, InsufficientDataException.class)) {
            log.info("文件上传失败，本地数据源有问题：{}", throwable.getMessage());
            return false;
        }
        // TLS、协议或 SDK 内部异常
        if (hasCause(throwable, SSLException.class)
                || hasCause(throwable, ProtocolException.class)
                || hasCause(throwable, InvalidResponseException.class)
                || hasCause(throwable, XmlParserException.class)
                || hasCause(throwable, InternalException.class)) {
            log.info("文件上传失败，TLS、协议或 SDK 内部异常：{}", throwable.getMessage());
            return false;
        }
        // 网络瞬时异常
        if (hasCause(throwable, SocketTimeoutException.class)
                || hasCause(throwable, ConnectException.class)
                || hasCause(throwable, SocketException.class)) {
            log.info("文件上传失败，网络瞬时异常：{}", throwable.getMessage());
            return true;
        }
        return false;
    }
    /**
     * 沿着 Throwable.getCause() 逐层查找指定类型的异常。
     * 找到则返回对应异常对象；
     * 找不到则返回 null。
     */
    private <T extends Throwable> T findCause(
            Throwable throwable,
            Class<T> type
    ) {
        Throwable current = throwable;
        while (current != null) {
            //在运行时动态检查异常对象是否匹配传入的泛型类型
            if (type.isInstance(current)) {
                return type.cast(current);
            }
             // 继续查找底层原因。
            current = current.getCause();
        }
        // 找不到指定类型的异常
        return null;
    }
     //判断异常链中是否包含指定类型的异常。
    private boolean hasCause(
            Throwable throwable,
            Class<? extends Throwable> type
    ) {
        return findCause(throwable, type) != null;
    }
}


