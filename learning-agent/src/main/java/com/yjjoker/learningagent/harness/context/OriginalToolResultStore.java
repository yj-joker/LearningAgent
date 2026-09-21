package com.yjjoker.learningagent.harness.context;

// 保存本次 Agent 运行期间的完整工具结果，供模型需要更多细节时按片段读取。
public interface OriginalToolResultStore {

    // Harness 在一次请求开始时设置会话范围，数据库实现据此限制查询范围。
    // 内存实现只依赖当前线程隔离，因此可以直接继承这个空实现。
    default void beginSession(Long sessionId) {
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
