package com.yjjoker.learningagent.harness.memory.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;

// 一次只整理一个用户或会话，不跨归属合并记忆。
@Getter
public class MemoryConsolidationSnapshot {
    private final MemoryScope scope;
    private final Long ownerId;
    private final long changeCount;
    private final long processedCount;
    private final List<MemoryConsolidationEntry> entries;

    // 固定本次引用和计数，模型修复与数据库保存使用同一份快照。
    public MemoryConsolidationSnapshot(MemoryConsolidationState state, List<MemoryConsolidationEntry> entries) {
        if (state == null || state.getScope() == null || state.getOwnerId() == null || state.getOwnerId() <= 0
                || state.getProcessedCount() < 0 || state.getChangeCount() < state.getProcessedCount()) {
            throw new IllegalArgumentException("记忆整理进度不合法");
        }
        // 重复 ID 或引用会让目标变得不明确，直接拒绝建立快照。
        Set<Long> ids = new HashSet<>();
        Set<String> refs = new HashSet<>();
        for (MemoryConsolidationEntry entry : entries) {
            if (entry == null || entry.getMemoryId() == null || entry.getMemoryId() <= 0
                    || entry.getMemoryRef() == null || entry.getMemoryRef().isBlank()
                    || !ids.add(entry.getMemoryId()) || !refs.add(entry.getMemoryRef())) {
                throw new IllegalArgumentException("记忆整理快照包含无效或重复目标");
            }
        }
        this.scope = state.getScope();
        this.ownerId = state.getOwnerId();
        this.changeCount = state.getChangeCount();
        this.processedCount = state.getProcessedCount();
        this.entries = List.copyOf(entries);
    }

    // 只解析本次发送过的引用，未知引用返回 null。
    public MemoryConsolidationEntry resolve(String memoryRef) {
        return entries.stream().filter(entry -> entry.getMemoryRef().equals(memoryRef)).findFirst().orElse(null);
    }
}
