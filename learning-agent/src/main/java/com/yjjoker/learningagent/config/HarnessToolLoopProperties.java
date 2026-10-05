package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 集中管理单次 Agent 任务的工具轮数上限。
@Data
@Validated
@ConfigurationProperties(prefix = "harness.tool-loop")
public class HarnessToolLoopProperties {

    // 一轮可以包含一批工具调用；这个值限制模型连续请求工具的批次数。
    @Min(1)
    private int maxRounds = 8;
}
