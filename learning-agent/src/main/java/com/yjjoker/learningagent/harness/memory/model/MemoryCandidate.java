package com.yjjoker.learningagent.harness.memory.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;

// 表示模型从本轮对话中提取出的候选记忆。
// 持久化服务会根据 operation 执行新增、更新或删除。
@Getter
@Setter
@NoArgsConstructor
public class MemoryCandidate {

    // USER 表示跨会话长期记忆，SESSION 表示只属于当前学习会话的记忆。
    private MemoryScope scope;

    // 操作由提取阶段给出，持久化服务只执行已声明且经过字段校验的操作。
    private MemoryOperation operation;

    // 修改或删除时列出所有目标；新增时为空，不允许模型猜数据库 ID。
    private List<String> targetMemoryRefs = List.of();

    // 摘录本轮用户原话，供后端检查候选是否有本轮输入作为依据。
    private String userEvidence;

    // 只有新增时需要提供 key；修改时保留目标原有的 key。
    private String memoryKey;

    // memoryTopic 用于按主题组织记忆索引。
    private String memoryTopic;

    // 摘要会进入索引上下文，应该短小且能说明正文主题。
    private String memorySummary;

    // 正文保存模型确认的事实，不能包含模型自行猜测的内容。
    private String memoryContent;
}
