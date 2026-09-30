package com.yjjoker.learningagent.harness.memory.model;

import lombok.Getter;
import java.util.HashSet;
import java.util.List;

// 后端写入完成的凭据，记录实际处理的范围和目标；不能由模型 JSON 构造。
@Getter
public final class MemoryWriteReceipt {
    private final MemoryOperation operation;
    private final MemoryScope scope;
    private final Long ownerId;
    private final List<Long> memoryIds;
    private final List<String> memoryKeys;

    // 事务服务正常返回后，用实际保存或删除的目标创建凭据，不使用模型猜出的 ID。
    public MemoryWriteReceipt(MemoryOperation operation, MemoryScope scope, Long ownerId,
                              List<Long> memoryIds, List<String> memoryKeys) {
        if (operation == null || scope == null || ownerId == null || ownerId <= 0
                || memoryIds == null || memoryKeys == null || memoryIds.isEmpty()
                || memoryIds.size() != memoryKeys.size()) {
            throw new IllegalArgumentException("记忆写入凭据必须包含操作、归属及完整目标");
        }
        // ID 和 key 一一对应；不接受空目标或重复目标来冒充一次完整写入。
        if (memoryIds.stream().anyMatch(id -> id == null || id <= 0)
                || memoryKeys.stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 128)
                || new HashSet<>(memoryIds).size() != memoryIds.size()
                || new HashSet<>(memoryKeys).size() != memoryKeys.size()) {
            throw new IllegalArgumentException("记忆写入凭据的目标不合法或重复");
        }
        this.operation = operation;
        this.scope = scope;
        this.ownerId = ownerId;
        this.memoryIds = List.copyOf(memoryIds);
        this.memoryKeys = List.copyOf(memoryKeys);
    }
}
