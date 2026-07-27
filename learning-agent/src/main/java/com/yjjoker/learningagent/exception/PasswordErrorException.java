package com.yjjoker.learningagent.exception;
// 密码错误异常
public class PasswordErrorException extends RuntimeException {
    public PasswordErrorException(String message) {
        super(message);
    }
}
