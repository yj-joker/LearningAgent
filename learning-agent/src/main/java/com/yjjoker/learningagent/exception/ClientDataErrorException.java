package com.yjjoker.learningagent.exception;
// 客户端数据错误异常
public class ClientDataErrorException extends RuntimeException {
    public ClientDataErrorException(String message) {
        super(message);
    }
}
