package com.yjjoker.learningagent.harness.memory;

// 描述候选记忆希望执行的生命周期操作。
public enum MemoryOperation {
    // 新主题或新事实进入记忆表。
    CREATE,
    // 用户明确修正已有记忆内容。
    UPDATE,
    // 用户明确要求忘记已有记忆，实际执行软删除。
    DELETE
}
