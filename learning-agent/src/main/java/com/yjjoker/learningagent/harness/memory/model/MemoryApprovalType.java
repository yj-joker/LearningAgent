package com.yjjoker.learningagent.harness.memory.model;

// 明确区分单条记忆候选和整批整理方案，读取 JSON 时不靠猜测判断格式。
public enum MemoryApprovalType {
    CHANGE,
    CONSOLIDATION
}
