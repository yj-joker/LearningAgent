package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 集中管理记忆提取格式修复的最大尝试次数。
@Data
@Validated
@ConfigurationProperties(prefix = "harness.memory.extraction.retry")
public class MemoryExtractionRetryProperties {

    // 最大尝试次数包含第一次提取请求。
    @Min(1)
    private int maxAttempts = 2;
}
