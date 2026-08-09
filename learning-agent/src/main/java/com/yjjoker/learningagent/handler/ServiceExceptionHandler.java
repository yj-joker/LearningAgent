package com.yjjoker.learningagent.handler;

import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class ServiceExceptionHandler {
    @ExceptionHandler(CreateErrorException.class)
    public Result<String> handleCreateErrorException(CreateErrorException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "400");
    }

    @ExceptionHandler(CourseStatusException.class)
    public Result<String> handleCreateStatusException(CourseStatusException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "400");
    }

    @ExceptionHandler(NotFountException.class)
    public Result<String> handleCourseNotFountException(NotFountException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "404");
    }

    @ExceptionHandler(LearningAgentServiceException.class)
    public Result<String> handleLearningAgentServiceException(LearningAgentServiceException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "500");
    }

    @ExceptionHandler(LearningSessionStatusException.class)
    public Result<String> handleLearningSessionStatusException(LearningSessionStatusException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "400");
    }

    @ExceptionHandler(ViolationOperationException.class)
    public Result<String> handleViolationOperationException(ViolationOperationException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "400");
    }

    @ExceptionHandler(PasswordErrorException.class)
    public Result<String> handlePasswordErrorException(PasswordErrorException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "403");
    }

    @ExceptionHandler(AnalysisErrorException.class)
    public Result<String> handleAnalysisErrorException(AnalysisErrorException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "500");
    }

    @ExceptionHandler(ExceededDatabaseLimitException.class)
    public Result<String> handleExceededDatabaseLimitException(ExceededDatabaseLimitException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "400");
    }

    @ExceptionHandler(DataIllegalException.class)
    public Result<String> handleDataIllegalException(DataIllegalException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "400");
    }

    @ExceptionHandler(ClientDataErrorException.class)
    public Result<String> handleClientDataErrorException(ClientDataErrorException e) {
        log.info(e.getMessage());
        return Result.error(e.getMessage(), "400");
    }
}
