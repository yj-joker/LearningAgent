package com.yjjoker.learningagent.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@Slf4j
//初始化桶，如果环境变量当中的桶名存在就不创建
public class MinioBucketInitializer implements ApplicationRunner {
    private final MinioProperties minioProperties;
    private final MinioClient minioClient;

    /**
     * 应用启动时确认目标桶存在；桶不存在则创建。
     * MinIO 暂时不可用时只记录错误，避免与文件存储无关的接口也无法启动。
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        String bucketName = minioProperties.getBucketName();
        try {
            // 判断桶是否存在
            boolean isExist = minioClient.bucketExists(BucketExistsArgs
                    .builder()
                    .bucket(bucketName)
                    .build()
            );
            if (!isExist) {
                // 创建桶
                minioClient.makeBucket(
                        MakeBucketArgs.builder()
                                .bucket(bucketName)
                                .build()
                );
                log.info("创建 MinIO 桶成功，bucketName={}", bucketName);
            } else {
                log.info("MinIO 桶已存在，bucketName={}", bucketName);
            }
        } catch (ErrorResponseException e) {
            String errorCode = e.errorResponse() == null ? "unknown" : e.errorResponse().code();
            if ("InvalidAccessKeyId".equals(errorCode) || "SignatureDoesNotMatch".equals(errorCode)) {
                log.error("初始化 MinIO 桶失败：应用配置的 Access Key 无法被 MinIO 识别，"
                                + "请检查 MINIO_ACCESS_KEY/MINIO_SECRET_KEY，endpoint={}，bucketName={}，errorCode={}",
                        minioProperties.getEndpoint(), bucketName, errorCode, e);
            } else if ("AccessDenied".equals(errorCode)) {
                log.error("初始化 MinIO 桶失败：MinIO 账号没有检查或创建桶的权限，"
                                + "请为应用配置相应权限，endpoint={}，bucketName={}，errorCode={}",
                        minioProperties.getEndpoint(), bucketName, errorCode, e);
            } else {
                log.error("初始化 MinIO 桶失败：S3/MinIO 返回错误，endpoint={}，bucketName={}，errorCode={}",
                        minioProperties.getEndpoint(), bucketName, errorCode, e);
            }
        } catch (Exception e) {
            log.error("初始化 MinIO 桶失败，文件上传和下载功能暂不可用，bucketName={}", bucketName, e);
        }
    }
}
