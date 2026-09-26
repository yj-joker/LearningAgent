package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.harness.llm.model.LlmMessage;

import java.util.List;

// 管理一个学习会话的模型消息历史，Harness 不需要了解具体数据库结构。
public interface ConversationMemoryService {

    //根据sessionId按照顺序加载对话
    List<LlmMessage> loadHistory(Long sessionId);

    //根据sessionId追加一条消息
    void appendMessage(Long sessionId, LlmMessage message);

    // 一次保存完整的一轮消息，避免数据库只留下半轮工具调用。
    void appendMessages(Long sessionId, List<LlmMessage> messages);

    // 将历史工具消息本轮产生的压缩副本回写数据库，避免后续请求重复加载完整原文再压缩。
    void updateToolContextCopies(Long sessionId, List<LlmMessage> messages);

    // 保存摘要覆盖点；旧消息保留在原表，由 loadHistory 按覆盖点排除。
    void replaceReplayableHistoryWithSummary(Long sessionId, LlmMessage summaryMessage);
}
