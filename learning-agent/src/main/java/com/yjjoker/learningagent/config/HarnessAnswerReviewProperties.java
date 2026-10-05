package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 集中管理最终回答审查的格式修复次数。
@Data
@Validated
@ConfigurationProperties(prefix = "harness.answer-review")
public class HarnessAnswerReviewProperties {

    // 最大尝试次数包含第一次审查请求；2 表示最多修复一次 JSON 格式。
    @Min(1)
    private int maxAttempts = 2;
}
