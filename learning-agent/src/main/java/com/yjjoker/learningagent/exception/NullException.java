package com.yjjoker.learningagent.exception;

//字段或对象为空异常
public class NullException extends RuntimeException {
    public NullException(String message) {
        super(message);
    }
}
