package com.yjjoker.learningagent.harness.skill.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// 检查点只记录技能身份，不再复制正文；内容指纹用于发现“正文改了但版本忘记更新”。
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillCheckpointEntry {
    private String name;
    private String version;
    private String contentHash;
}
