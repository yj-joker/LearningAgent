package com.yjjoker.learningagent.harness.service;

import com.yjjoker.learningagent.vo.AgentRunResult;

// Web 业务层接口：规定 Harness 对项目其他模块公开的能力。
// Controller 或其他业务服务只需要依赖这个接口，不需要知道 Harness 内部使用哪一家 LLM。
public interface AgentHarnessService {

    // sessionId 用于读取同一学习会话的历史，userMessage 是本轮用户输入。
    // 返回回答或等待审批状态，不能再只用一个字符串表示所有结果。
    AgentRunResult run(Long sessionId, String userMessage);

    // 通过原 runId 继续已审批任务，不把批准当成新的用户问题。
    AgentRunResult resume(String runId);

    void deleteSession(Long sessionId);
}
