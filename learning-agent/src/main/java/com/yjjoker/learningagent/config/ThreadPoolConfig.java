package com.yjjoker.learningagent.config;

import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.utils.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@Slf4j
public class ThreadPoolConfig {

    /**
     * 创建业务异步线程池。
     */
    @Bean("documentParseExecutor")
    public ThreadPoolTaskExecutor documentParseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);//核心线程数
        executor.setMaxPoolSize(40);//最大线程数
        executor.setQueueCapacity(100);//队列容量
        executor.setKeepAliveSeconds(60);//线程空闲时间
        executor.setThreadNamePrefix("doc-parse-");//线程名称前缀
        /*
         * 拒绝策略：当线程达到最大时，队列满时触发。作用：抛出异常。
         * 不能使用CallerRunsPolicy，会导致系统假死。
         */
        executor.setRejectedExecutionHandler(
               new ThreadPoolExecutor.AbortPolicy()
        );
        //设置任务包装器，用于复制ThreadLocal数据
        executor.setTaskDecorator(new ContextCopingDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);//spring容器关闭时等待任务完成
        executor.setAwaitTerminationSeconds(30);//等待时间单位秒
        executor.initialize();//初始化线程池
        log.info("线程池初始化完成");
        return executor;
    }
    /**
     * 创建一个任务装饰器，用于复制ThreadLocal数据
     * 执行时机：任务提交之前。
     * 作用：当异步执行一个解析任务时，该类会讲任务包装，到时候线程池里的线程执行的是包装任务。
     * */
    private static class ContextCopingDecorator implements TaskDecorator {
        @Override
        public @NonNull Runnable decorate(@NonNull Runnable runnable) {
            Long userId = BaseContext.getCurrentId(); // 获取当前线程的 userId
            UserRoleEnum userRole = BaseContext.getCurrentRole();// 获取当前线程的 userRole
            //对任务进行包装
            return () -> {
                try {
                    BaseContext.setCurrentId(userId); // 设置当前线程的 userId
                    BaseContext.setCurrentRole(userRole); // 设置当前线程的 userRole
                    log.info("设置当前线程的 userRole成功：{}", userRole);
                    log.info("设置当前线程的 userId成功：{}", userId);
                    runnable.run(); // 执行任务
                } finally {
                    BaseContext.removeCurrentId(); // 移除当前线程的 userId
                    BaseContext.removeCurrentRole(); // 移除当前线程的 userRole
                    log.info("移除当前线程的 userRole成功：{}", userRole);
                    log.info("移除当前线程的 userId成功：{}", userId);
                }
            };
        }
    }

}
