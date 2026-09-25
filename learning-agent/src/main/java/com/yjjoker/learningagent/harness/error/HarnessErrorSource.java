package com.yjjoker.learningagent.harness.error;

// 标记错误产生的位置，后续重试策略可以按来源决定是否重试。
public enum HarnessErrorSource {
    TOOL,
    HOOK,
    LLM,
    CONTEXT,
    HARNESS
}
