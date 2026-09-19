package com.yjjoker.learningagent.client;

import com.yjjoker.learningagent.config.MinioProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

// MinIO 客户端配置。
@Configuration("minioClientConfig")
@Slf4j
public class MinioClient {

    // 根据 application.yml 中的 MinIO 配置创建可复用的 MinioClient。
    @Bean
    public io.minio.MinioClient minioClient(MinioProperties minioProperties) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                // 连接超时
                .connectTimeout(3, TimeUnit.SECONDS)
                // 读超时
                .readTimeout(60, TimeUnit.SECONDS)
                // 写超时
                .writeTimeout(10, TimeUnit.MINUTES)
                .build();
        log.info("MinIO 客户端初始化完成");
        return io.minio.MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .httpClient(httpClient)
                .build();
    }
}
