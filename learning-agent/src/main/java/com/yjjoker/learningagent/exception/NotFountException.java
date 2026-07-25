package com.yjjoker.learningagent.exception;

//课程不存在异常
public class NotFountException extends RuntimeException {
    public NotFountException(String message) {
        super(message);
    }
}
