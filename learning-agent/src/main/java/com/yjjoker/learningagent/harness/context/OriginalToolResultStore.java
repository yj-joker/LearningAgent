package com.yjjoker.learningagent.harness.context;

import com.yjjoker.learningagent.harness.model.AgentMode;

// 保存本次 Agent 运行期间的完整工具结果，供模型需要更多细节时按片段读取。
public interface OriginalToolResultStore {

    // Harness 同时设置会话和模式范围，数据库恢复不能跨会话或跨模式读取。
    // 纯内存实现按当前线程隔离，因此不需要额外保存这两个值。
    default void beginSession(Long sessionId, AgentMode agentMode) {
    }

    // toolCallId 是模型本次工具调用的唯一标识，originalResult 是完整 JSON 结果。
    void save(String toolCallId, String originalResult);

    // 返回指定范围的原文；offset 从 0 开始，limit 控制单次恢复量。
    String read(String toolCallId, int offset, int limit);

    // 返回原文总长度，找不到时返回 -1。
    int length(String toolCallId);

    // 任务结束后清理当前运行的结果，避免请求之间互相读取或内存持续增长。
    void clear();
}
