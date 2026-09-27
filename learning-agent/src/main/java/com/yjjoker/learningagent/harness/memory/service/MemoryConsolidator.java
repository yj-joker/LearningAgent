package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationPlan;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationSnapshot;

// 模型只负责提出方案，不直接操作数据库。
public interface MemoryConsolidator {
    // 根据完整记忆快照提出合并组和需要保留的冲突组。
    MemoryConsolidationPlan consolidate(MemoryConsolidationSnapshot snapshot);
}
