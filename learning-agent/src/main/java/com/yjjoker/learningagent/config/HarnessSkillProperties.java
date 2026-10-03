package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 限制一次任务加载的技能规模；整个模型请求仍受原上下文安全水位限制。
@Data
@Validated
@ConfigurationProperties(prefix = "harness.skills")
public class HarnessSkillProperties {
    // 重复读取同一技能不增加数量，审批恢复也不重置已加载集合。
    @Min(1)
    private int maxActiveSkills = 3;
    // 统计已加载正文的字符，不等同于 token；索引和其他消息另由 ContextManager 计算。
    @Min(1)
    private int maxActiveCharacters = 12_000;
}
