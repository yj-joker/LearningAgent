package com.yjjoker.learningagent.notification;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

// 管理已认证用户的通知连接；不接收批准指令，也不传输记忆正文。
@Component
@Slf4j
public class ApprovalWebSocketHandler extends TextWebSocketHandler {
    private static final String LAST_HEARTBEAT = "approvalLastHeartbeat";
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // 为同一用户保留多个标签页，同时限制连接数。
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object userId = session.getAttributes().get(ApprovalHandshakeInterceptor.USER_ID);
        boolean accepted;
        synchronized (sessions) {
            accepted = userId instanceof Long && valid(session) && sessions.size() < 10000
                    && sessions.values().stream().filter(item -> userId.equals(
                            item.getAttributes().get(ApprovalHandshakeInterceptor.USER_ID))).count() < 8;
            if (accepted) {
                session.setTextMessageSizeLimit(128);
                session.getAttributes().put(LAST_HEARTBEAT, Instant.now());
                // 审批线程和心跳可能同时发送，装饰器避免并发写坏同一条连接。
                sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 3000, 8192));
            }
        }
        if (!accepted) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        send(sessions.get(session.getId()), "READY");
        log.info("审批通知已连接，userId={}，connectionCount={}", userId, sessions.size());
    }

    // 仅允许心跳；审批操作仍走有权限校验的 HTTP 接口。
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!"ping".equals(message.getPayload()) || !valid(session)) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put(LAST_HEARTBEAT, Instant.now());
        send(sessions.get(session.getId()), "PONG");
    }

    // 状态提示发给当前用户的全部标签页，不向其他用户广播。
    public void notifyUser(Long userId) {
        int sent = 0;
        for (WebSocketSession session : sessions.values()) {
            if (userId.equals(session.getAttributes().get(ApprovalHandshakeInterceptor.USER_ID))
                    && send(session, "APPROVALS_CHANGED")) sent++;
        }
        log.info("审批变更通知已发送，userId={}，connectionCount={}", userId, sent);
    }

    // 登录到期或浏览器长时间失联后释放连接；不会占着线程等待用户审批。
    @Scheduled(fixedDelay = 30000)
    public void removeExpiredConnections() {
        for (WebSocketSession session : sessions.values()) {
            Instant heartbeat = (Instant) session.getAttributes().get(LAST_HEARTBEAT);
            if (!valid(session) || heartbeat == null || heartbeat.isBefore(Instant.now().minusSeconds(90))) {
                close(session, CloseStatus.POLICY_VIOLATION);
            }
        }
    }

    // 连接关闭时移除索引，不保存任何业务审批状态。
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.debug("审批通知连接关闭，code={}，connectionCount={}", status.getCode(), sessions.size());
    }

    // 网络异常仅关闭通知连接，前端重连后重新查询数据库。
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        close(session, CloseStatus.SERVER_ERROR);
    }

    // 每次发送前再次检查认证期限，不能靠持续心跳延长登录。
    private boolean valid(WebSocketSession session) {
        Object expiresAt = session.getAttributes().get(ApprovalHandshakeInterceptor.AUTH_EXPIRES_AT);
        return expiresAt instanceof Instant instant && instant.isAfter(Instant.now());
    }

    // 消息只表示“数据变了”；正文由已有 HTTP 接口按权限读取。
    private boolean send(WebSocketSession session, String type) {
        if (session == null) return false;
        if (!session.isOpen() || !valid(session)) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return false;
        }
        try {
            session.sendMessage(new TextMessage("{\"type\":\"" + type + "\"}"));
            return true;
        } catch (Exception exception) {
            // 不打印异常正文，避免第三方异常携带请求地址或敏感信息。
            log.warn("审批通知发送失败，将由重连补查，errorType={}", exception.getClass().getSimpleName());
            close(session, CloseStatus.SERVER_ERROR);
            return false;
        }
    }

    // 先移除连接，再尝试关闭网络；关闭失败不影响其他用户的通知。
    private void close(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        try { session.close(status); }
        catch (IOException exception) { log.debug("审批通知连接已不可用"); }
    }
}
