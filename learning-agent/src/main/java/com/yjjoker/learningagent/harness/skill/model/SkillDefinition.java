package com.yjjoker.learningagent.harness.skill.model;

import lombok.Getter;

import java.util.Objects;

// 完整技能定义；正文只是说明文本，注册或读取它都不会执行工具或脚本。
@Getter
public final class SkillDefinition {
    private final SkillIndex index;
    private final String content;

    // 将索引和正文固定为同一个启动快照，读取时不重新打开文件。
    public SkillDefinition(SkillIndex index, String content) {
        this.index = Objects.requireNonNull(index, "Skill 索引不能为空");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Skill 正文不能为空");
        }
        this.content = content.strip();
    }
}
