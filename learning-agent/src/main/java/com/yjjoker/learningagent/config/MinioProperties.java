package com.yjjoker.learningagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

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
    @Pattern(regexp = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$",
            message = "MinIO bucket 名称必须是3到63位小写字母、数字、点或连字符")
    private String bucketName;
}
