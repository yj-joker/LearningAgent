package com.yjjoker.learningagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
@Slf4j
public class AsyncExceptionConfig implements AsyncConfigurer {
/**
 * 异步方法执行异常处理，兜底异常处理
 * */
    @Override
    public AsyncUncaughtExceptionHandler
    getAsyncUncaughtExceptionHandler() {
        return (exception, method, params) -> {
            log.error(
                    "异步方法执行失败，method={}, params={}",
                    method.getName(),
                    params,
                    exception
            );
        };
    }
}