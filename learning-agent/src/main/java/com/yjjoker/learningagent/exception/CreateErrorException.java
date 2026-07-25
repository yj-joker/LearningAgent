package com.yjjoker.learningagent.exception;
//非法创建异常
public class CreateErrorException extends RuntimeException {
    public CreateErrorException(String message) {
        super(message);
    }
}
