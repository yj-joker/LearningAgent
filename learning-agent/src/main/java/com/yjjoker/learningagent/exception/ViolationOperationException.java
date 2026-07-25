package com.yjjoker.learningagent.exception;
// 违规操作异常
public class ViolationOperationException extends RuntimeException {
    public ViolationOperationException(String message) {
        super(message);
    }
}
