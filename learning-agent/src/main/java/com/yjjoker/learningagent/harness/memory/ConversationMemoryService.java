package com.yjjoker.learningagent.harness.memory;

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
}
