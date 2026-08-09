package com.yjjoker.learningagent.exception;
//超过数据库数据类型限制异常
public class ExceededDatabaseLimitException extends RuntimeException {
    public ExceededDatabaseLimitException(String message) {
        super(message);
    }
}
