package com.yjjoker.learningagent.harness.memory;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 表示模型从本轮对话中提取出的候选记忆。
// 当前阶段只负责生成和校验，后续阶段再决定新增、更新或丢弃。
@Getter
@Setter
@NoArgsConstructor
public class MemoryCandidate {

    // USER 表示跨会话长期记忆，SESSION 表示只属于当前学习会话的记忆。
    private MemoryScope scope;

    // memoryKey 是后续去重和更新的稳定业务键，不是数据库主键。
    private String memoryKey;

    // memoryTopic 用于按主题组织记忆索引。
    private String memoryTopic;

    // 摘要会进入索引上下文，应该短小且能说明正文主题。
    private String memorySummary;

    // 正文保存模型确认的事实，不能包含模型自行猜测的内容。
    private String memoryContent;
}
