package com.yjjoker.learningagent.harness.context;

import com.yjjoker.learningagent.harness.llm.model.LlmMessage;

import java.util.List;

// 负责把旧上下文提炼成较短的事实摘要，不参与普通 Agent Loop 的工具决策。
public interface ContextSummarizer {

    // 输入待压缩的旧消息，返回可直接放回上下文的摘要正文。
    String summarize(List<LlmMessage> messages);
}
