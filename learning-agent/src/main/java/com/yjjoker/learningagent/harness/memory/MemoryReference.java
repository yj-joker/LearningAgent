package com.yjjoker.learningagent.harness.memory;

// 服务端保存的记忆引用目标；模型只能看到 memoryRef，不会看到这里的数据库 ID。
public class MemoryReference {

    private final MemoryScope scope;
    private final Long ownerId;
    private final Long memoryId;

    public MemoryReference(MemoryScope scope, Long ownerId, Long memoryId) {
        this.scope = scope;
        this.ownerId = ownerId;
        this.memoryId = memoryId;
    }

    // 返回记忆范围，用于选择长期记忆或会话记忆查询。
    public MemoryScope getScope() {
        return scope;
    }

    // 返回已在服务端校验过的用户 ID 或会话 ID。
    public Long getOwnerId() {
        return ownerId;
    }

    // 返回数据库内部记忆 ID，只供服务端查询使用。
    public Long getMemoryId() {
        return memoryId;
    }
}
