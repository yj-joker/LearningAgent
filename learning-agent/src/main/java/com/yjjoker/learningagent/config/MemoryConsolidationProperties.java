package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 配置记忆整理的触发频率和单次处理上限。
@Data
@Validated
@ConfigurationProperties(prefix = "harness.memory.consolidation")
public class MemoryConsolidationProperties {
    // 关闭后继续提取和保存记忆，但不请求整理模型。
    private boolean enabled = true;

    // 每累计这么多次实际记录变更，再整理一次；两类记忆分别计数。
    @Min(1)
    private int changeThreshold = 20;

    // 本阶段完整读取一个范围的记忆，超限时保留原数据，后续再支持分批。
    @Min(2)
    @Max(1000)
    private int maxMemories = 100;

    // 这是整理请求的字符预算，不是厂商的 token 数。正文不截断后再合并。
    @Min(2000)
    private int maxInputCharacters = 40000;

    // 格式或引用校验失败时允许修复，次数包含第一次调用。
    @Min(1)
    @Max(3)
    private int maxAttempts = 2;
}
