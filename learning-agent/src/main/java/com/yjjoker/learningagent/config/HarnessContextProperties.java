package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 上下文管理的运行参数集中放在这里，避免把模型窗口限制写死在 Harness 循环中。
@ConfigurationProperties(prefix = "harness.context")
@Validated
@Data
public class HarnessContextProperties {

    // MVP 阶段用字符数做近似估算；它不是厂商真实 token 数，但适合先验证压缩流程。
    @Min(1)
    private int maxContextCharacters = 40_000;

    // 工具结果的 content 正文最多保留多少字符，避免 RAG 或联网结果占满整个上下文。
    @Min(1)
    private int maxToolResultCharacters = 8_000;
}
