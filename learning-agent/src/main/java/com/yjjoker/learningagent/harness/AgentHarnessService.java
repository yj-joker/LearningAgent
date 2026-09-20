package com.yjjoker.learningagent.harness;

// Web 业务层接口：规定 Harness 对项目其他模块公开的能力。
// Controller 或其他业务服务只需要依赖这个接口，不需要知道 Harness 内部使用哪一家 LLM。
public interface AgentHarnessService {

    // 接收一次用户输入并返回 Harness 的最终回复。
    // 内部可能多次调用 LLM 并执行工具，但 Controller 只关心最后整理好的自然语言答案。
    String run(String userMessage);
}
