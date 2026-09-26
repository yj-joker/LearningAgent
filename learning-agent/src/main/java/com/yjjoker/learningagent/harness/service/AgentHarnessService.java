package com.yjjoker.learningagent.harness.service;

// Web 业务层接口：规定 Harness 对项目其他模块公开的能力。
// Controller 或其他业务服务只需要依赖这个接口，不需要知道 Harness 内部使用哪一家 LLM。
public interface AgentHarnessService {

    // sessionId 用于读取同一学习会话的历史，userMessage 是本轮用户输入。
    String run(Long sessionId, String userMessage);

    void deleteSession(Long sessionId);
}
