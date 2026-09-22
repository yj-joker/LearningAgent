package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

    // 单次 Agent Loop 最多允许模型调用几次原始结果恢复工具。
    @Min(1)
    private int maxRecoveryCallsPerRun = 2;

    // 单次 Agent Loop 中所有成功恢复结果的累计字符上限。
    @Min(1)
    private int maxRecoveryCharactersPerRun = 600;

    // 每次模型请求最多使用上下文的 95%，剩余空间留给模型生成和后续控制消息。
    @DecimalMin("0.1")
    @DecimalMax("0.99")
    private double safeContextRatio = 0.95;

    // 触发压缩后尽量回落到 80%，为后续工具调用预留空间；达不到目标但低于安全上限仍可继续。
    @DecimalMin("0.1")
    @DecimalMax("0.94")
    private double compressionTargetRatio = 0.80;
}
