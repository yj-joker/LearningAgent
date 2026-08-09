package com.yjjoker.learningagent.exception;
// 数据非法异常
public class DataIllegalException extends RuntimeException {
    public DataIllegalException(String message) {
        super(message);
    }
}
