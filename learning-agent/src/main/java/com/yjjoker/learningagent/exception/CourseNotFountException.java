package com.yjjoker.learningagent.exception;

//课程不存在异常
public class CourseNotFountException extends RuntimeException {
    public CourseNotFountException(String message) {
        super(message);
    }
}
