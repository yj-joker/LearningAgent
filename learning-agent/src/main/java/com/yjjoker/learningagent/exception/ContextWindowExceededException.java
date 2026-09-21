package com.yjjoker.learningagent.exception;

// 工具结果压缩后仍超过上下文上限时使用，摘要功能将在后续阶段处理这种情况。
public class ContextWindowExceededException extends LearningAgentServiceException {

    public ContextWindowExceededException(String message) {
        super(message);
    }
}
