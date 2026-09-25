package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 集中管理 LLM 重试参数，避免重试规则散落在 Agent Loop 和模型客户端中。
@Data
@Validated
@ConfigurationProperties(prefix = "harness.llm.retry")
public class HarnessLlmRetryProperties {

    // 最大尝试次数包含第一次请求；值为 3 时最多发送三次请求。
    @Min(1)
    private int maxAttempts = 3;

    // 第一次失败后等待的时间；后续等待时间会逐次翻倍。
    @Min(0)
    private long initialBackoffMillis = 200;

    // 限制单次等待上限，避免限流期间长时间占用请求线程。
    @Min(0)
    private long maxBackoffMillis = 2_000;
}
