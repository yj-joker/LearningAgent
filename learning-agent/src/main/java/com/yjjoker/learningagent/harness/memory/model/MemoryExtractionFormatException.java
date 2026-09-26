package com.yjjoker.learningagent.harness.memory.model;

// 表示模型返回的记忆提取结果无法通过格式或字段校验。
public class MemoryExtractionFormatException extends IllegalStateException {

    public MemoryExtractionFormatException(String message) {
        super(message);
    }

    public MemoryExtractionFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
