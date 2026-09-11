package com.yjjoker.learningagent.config;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * MinIO 客户端配置。
 */
@Configuration
@Slf4j
public class MinioConfig {

    /**
     * 根据 application.yml 中的 minio 配置创建可复用的 MinioClient。
     */
    @Bean
    public MinioClient minioClient(MinioProperties minioProperties) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)//握手超时
                .readTimeout(60, TimeUnit.SECONDS)//读超时
                .writeTimeout(10, TimeUnit.MINUTES)//写超时（大文件）
                .build();
        log.info("MinIO 客户端初始化完成");
        return MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .httpClient(httpClient)
                .build();
    }
}
