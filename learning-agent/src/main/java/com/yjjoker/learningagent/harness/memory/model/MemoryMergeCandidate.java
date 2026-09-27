package com.yjjoker.learningagent.harness.memory.model;

import java.util.List;
import lombok.Data;

// 模型提出一组同义记忆的合并方案，由后端校验后执行。
@Data
public class MemoryMergeCandidate {
    // 保留其中一条原记录的 ID 和 key，其余来源只软删除。
    private String keepRef;
    // 至少两个引用，必须包含 keepRef。
    private List<String> sourceRefs = List.of();
    private String memoryTopic;
    private String memorySummary;
    // 合并后的正文应保留所有不冲突的细节，不能只复制索引摘要。
    private String memoryContent;
}
