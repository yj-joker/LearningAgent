package com.yjjoker.learningagent.notification;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

// 浏览器先通过已登录的 HTTP 请求领票，再用票据建立连接；不把 JWT 放进 URL。
@Component
@Slf4j
public class ApprovalHandshakeInterceptor implements HandshakeInterceptor {
    public static final String USER_ID = "approvalUserId";
    public static final String AUTH_EXPIRES_AT = "approvalAuthExpiresAt";
    private final SecureRandom random = new SecureRandom();
    private final Map<String, ApprovalSocketTicket> tickets = new HashMap<>();

    // 签发随机票据；短锁只保护内存，不查询数据库或等待网络。
    public synchronized String issue(Long userId, Instant authenticationExpiresAt) {
        Instant now = Instant.now();
        tickets.values().removeIf(ticket -> !ticket.getExpiresAt().isAfter(now));
        if (userId == null || userId <= 0 || !authenticationExpiresAt.isAfter(now)) {
            throw new ClientDataErrorException("请重新登录后连接审批通知");
        }
        // 限制未使用票据的数量，避免重复连接申请不断占用内存。
        if (tickets.size() >= 10000 || tickets.values().stream()
                .filter(ticket -> userId.equals(ticket.getUserId())).count() >= 8) {
            throw new ClientDataErrorException("通知连接申请过于频繁，请稍后重试");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plusSeconds(30);
        tickets.put(ticket, new ApprovalSocketTicket(userId,
                expiresAt.isBefore(authenticationExpiresAt) ? expiresAt : authenticationExpiresAt,
                authenticationExpiresAt));
        return ticket;
    }

    // 消费票据与删除在同一把锁内完成，两次并发握手只能成功一次。
    @Override
    public synchronized boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                WebSocketHandler handler, Map<String, Object> attributes) {
        String value = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        ApprovalSocketTicket ticket = tickets.remove(value);
        if (ticket == null || !ticket.getExpiresAt().isAfter(Instant.now())) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.debug("审批通知握手被拒绝：票据不存在、已使用或已过期");
            return false;
        }
        // 连接只绑定认证过的身份；忽略 URL 中客户端自行提交的 userId。
        attributes.put(USER_ID, ticket.getUserId());
        attributes.put(AUTH_EXPIRES_AT, ticket.getAuthenticationExpiresAt());
        return true;
    }

    // 票据握手时已消费；握手失败后必须重新领票，不回收旧票据重用。
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        if (exception != null) log.debug("审批通知握手未完成，需重新申请票据");
    }
}
