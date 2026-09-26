package com.yjjoker.learningagent.harness.memory.model;

import lombok.Getter;

// 服务端保存的记忆引用目标；模型只能看到 memoryRef，不会看到这里的数据库 ID。
@Getter
public class MemoryReference {

    // 返回记忆范围，用于选择长期记忆或会话记忆查询。
    private final MemoryScope scope;
    // 返回已在服务端校验过的用户 ID 或会话 ID。
    private final Long ownerId;
    // 返回数据库内部记忆 ID，只供服务端查询使用。
    private final Long memoryId;

    public MemoryReference(MemoryScope scope, Long ownerId, Long memoryId) {
        this.scope = scope;
        this.ownerId = ownerId;
        this.memoryId = memoryId;
    }

}
