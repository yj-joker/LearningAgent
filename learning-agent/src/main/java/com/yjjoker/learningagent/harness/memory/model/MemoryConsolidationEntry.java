package com.yjjoker.learningagent.harness.memory.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 保存整理前的完整正文快照，写入时检查模型看到的数据是否已经变化。
@Getter
@AllArgsConstructor
public class MemoryConsolidationEntry {
    // 仅本次整理有效；模型不接触数据库主键和归属 ID。
    private final String memoryRef;
    private final Long memoryId;
    private final String memoryKey;
    private final String memoryTopic;
    private final String memorySummary;
    private final String memoryContent;
    private final LocalDateTime updatedAt;
}
