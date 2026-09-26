package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.harness.memory.model.MemoryCandidate;

import java.util.List;

// 从本轮用户问题和最终回答中提取结构化记忆候选。
// 当前接口不负责数据库持久化，避免提取、去重和更新逻辑耦合在一起。
public interface MemoryExtractionService {

    // 返回本轮可能需要保存的记忆；没有合适内容时返回空列表。
    List<MemoryCandidate> extract(Long sessionId, String userMessage, String assistantAnswer);
}
