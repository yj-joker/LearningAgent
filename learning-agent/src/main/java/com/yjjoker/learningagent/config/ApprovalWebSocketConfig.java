package com.yjjoker.learningagent.config;

import com.yjjoker.learningagent.notification.ApprovalHandshakeInterceptor;
import com.yjjoker.learningagent.notification.ApprovalWebSocketHandler;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

// 注册原生 WebSocket 入口，不引入额外消息代理或改变现有 HTTP 审批协议。
@Configuration
@EnableWebSocket
public class ApprovalWebSocketConfig implements WebSocketConfigurer {
    private final ApprovalWebSocketHandler handler;
    private final ApprovalHandshakeInterceptor handshake;
    private final String[] origins;

    // 明确允许的网页来源；生产部署时配置实际域名，不使用通配符。
    public ApprovalWebSocketConfig(ApprovalWebSocketHandler handler, ApprovalHandshakeInterceptor handshake,
            @Value("${approval.websocket.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String[] origins) {
        this.handler = handler;
        this.handshake = handshake;
        this.origins = origins;
    }

    // 先校验网页来源和票据，再绑定服务器确认的用户身份。
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/agent/approvals/socket")
                .addInterceptors(handshake).setAllowedOrigins(origins);
    }

    // 通知只做短网络写入；队列有上限，不能挤占文档或记忆任务的线程池。
    @Bean("approvalNotificationExecutor")
    public static ThreadPoolTaskExecutor approvalNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("approval-notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
