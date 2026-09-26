package com.yjjoker.learningagent.harness.memory.model;

import lombok.Getter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 同一份快照贯穿提取、修复和保存，避免 memoryRef 在中途指向另一条记忆。
@Getter
public class MemoryExtractionContext {
    private final Long userId;
    private final Long sessionId;
    private final List<MemoryExtractionTarget> targets;
    private final Map<String, MemoryExtractionTarget> targetsByRef;

    // 给本次索引建立独立映射；不复用历史对话中的引用。
    public MemoryExtractionContext(Long userId, Long sessionId, MemoryIndexSnapshot index) {
        if (userId == null || userId <= 0 || sessionId == null || sessionId <= 0) {
            throw new IllegalArgumentException("记忆提取的用户和会话 ID 必须大于 0");
        }
        this.userId = userId;
        this.sessionId = sessionId;
        List<MemoryExtractionTarget> entries = new ArrayList<>();
        // 两类记忆统一编号，但各自保留归属范围。
        index.getUserMemories().forEach(memory -> {
            requireOwner(userId, memory.getUserId(), memory.getId());
            entries.add(new MemoryExtractionTarget("memory_" + (entries.size() + 1),
                    MemoryScope.USER, userId, memory.getId(), memory.getMemoryKey(),
                    memory.getMemoryTopic(), memory.getMemorySummary(), memory.getUpdatedAt()));
        });
        index.getSessionMemories().forEach(memory -> {
            requireOwner(sessionId, memory.getSessionId(), memory.getId());
            entries.add(new MemoryExtractionTarget("memory_" + (entries.size() + 1),
                    MemoryScope.SESSION, sessionId, memory.getId(), memory.getMemoryKey(),
                    memory.getMemoryTopic(), memory.getMemorySummary(), memory.getUpdatedAt()));
        });
        // 复制字段和集合，让外部列表或实体的变化不影响本次映射。
        this.targets = List.copyOf(entries);
        Map<String, MemoryExtractionTarget> references = new LinkedHashMap<>();
        entries.forEach(entry -> references.put(entry.getMemoryRef(), entry));
        this.targetsByRef = Map.copyOf(references);
    }

    // 查找本次提供过的引用，未知引用返回 null。
    public MemoryExtractionTarget resolve(String memoryRef) {
        return memoryRef == null ? null : targetsByRef.get(memoryRef);
    }

    // 拒绝把其他用户或会话的记忆加入本次索引。
    private void requireOwner(Long expectedOwner, Long actualOwner, Long memoryId) {
        if (!Objects.equals(expectedOwner, actualOwner) || memoryId == null || memoryId <= 0) {
            throw new IllegalArgumentException("记忆索引归属或 ID 不合法");
        }
    }
}
