package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.model.AgentMode;

import java.util.List;

// 管理一个学习会话的模型消息历史，Harness 不需要了解具体数据库结构。
public interface ConversationMemoryService {

    // 只加载当前模式的摘要和消息，不把问答内容与专注执行记录混在一起。
    List<LlmMessage> loadHistory(Long sessionId, AgentMode agentMode);

    // 按当前模式追加一条消息。
    void appendMessage(Long sessionId, AgentMode agentMode, LlmMessage message);

    // 在同一事务中按当前模式保存完整的一轮消息。
    void appendMessages(Long sessionId, AgentMode agentMode, List<LlmMessage> messages);

    // 将当前模式历史工具消息的压缩副本回写数据库，避免跨模式修改相同调用编号。
    void updateToolContextCopies(Long sessionId, AgentMode agentMode, List<LlmMessage> messages);

    // 保存当前模式的摘要覆盖点；其他模式的历史不受影响。
    void replaceReplayableHistoryWithSummary(Long sessionId, AgentMode agentMode, LlmMessage summaryMessage);
}
