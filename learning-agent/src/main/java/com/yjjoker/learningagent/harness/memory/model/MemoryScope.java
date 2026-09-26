package com.yjjoker.learningagent.harness.memory.model;

// 区分记忆属于用户，还是只属于当前学习会话。
public enum MemoryScope {
    // USER 表示跨会话的长期记忆。
    USER,
    // SESSION 表示当前学习会话的记忆。
    SESSION
}
