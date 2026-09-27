package com.yjjoker.learningagent.harness.memory.model;

// 只标记模型可以修正的输出问题；网络错误继续使用已有重试机制。
public class MemoryConsolidationFormatException extends RuntimeException {
    // 保存简短的校验原因，不拼接模型返回的完整正文。
    public MemoryConsolidationFormatException(String message) {
        super(message);
    }
}
