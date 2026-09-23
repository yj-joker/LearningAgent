package com.yjjoker.learningagent.exception;

// 工具结果压缩和当前阶段摘要都无法容纳上下文时使用。
public class ContextWindowExceededException extends LearningAgentServiceException {

    public ContextWindowExceededException(String message) {
        super(message);
    }
}
