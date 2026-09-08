package com.yjjoker.learningagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties(prefix = "minio")
@Validated
@Data
public class MinioProperties {
    @NotBlank
    private String endpoint;
    @NotBlank
    private String accessKey;
    @NotBlank
    private String secretKey;
    @NotBlank
    private String bucketName;
}
