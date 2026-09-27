package com.yjjoker.learningagent.harness.memory.model;

import java.util.List;
import lombok.Data;

// 只允许合并和报告冲突，不允许整理模型凭空新增事实或任意删除记忆。
@Data
public class MemoryConsolidationPlan {
    private List<MemoryMergeCandidate> merges = List.of();
    // 每组引用代表一组无法确认的冲突；本阶段保留这些原记录。
    private List<List<String>> conflicts = List.of();
}
