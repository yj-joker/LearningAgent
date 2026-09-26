package com.yjjoker.learningagent.harness.memory.model;

import lombok.Getter;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

// 保存提取时的一条索引快照；数据库 ID 和归属只供后端使用。
@Getter
@AllArgsConstructor
public class MemoryExtractionTarget {
    // 模型只能用本次索引提供的短引用选择目标。
    private final String memoryRef;
    private final MemoryScope scope;
    private final Long ownerId;
    private final Long memoryId;
    // 保存原 key 和索引内容，写入前检查记忆是否已经改变。
    private final String memoryKey;
    private final String memoryTopic;
    private final String memorySummary;
    private final LocalDateTime updatedAt;
}
