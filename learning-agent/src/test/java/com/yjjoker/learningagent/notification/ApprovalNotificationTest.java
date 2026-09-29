package com.yjjoker.learningagent.notification;

import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 用受控连接验证安全边界；真实握手与网络收发由另一个集成测试验证。
class ApprovalNotificationTest {
    // 票据只允许消费一次，URL 伪造的 userId 不参与身份选择。
    @Test
    void ticketIsSingleUseAndBindsAuthenticatedUser() {
        var tickets = new ApprovalHandshakeInterceptor();
        String ticket = tickets.issue(7L, Instant.now().plusSeconds(60));
        var request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/agent/approvals/socket?ticket=" + ticket + "&userId=99"));
        var response = mock(ServerHttpResponse.class);
        var attributes = new HashMap<String, Object>();
        assertTrue(tickets.beforeHandshake(request, response, null, attributes));
        assertEquals(7L, attributes.get(ApprovalHandshakeInterceptor.USER_ID));
        assertFalse(tickets.beforeHandshake(request, response, null, new HashMap<>()));
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    // 已失效登录和票据申请洪泛均不能获得新凭据。
    @Test
    void boundsTicketIssuance() {
        var tickets = new ApprovalHandshakeInterceptor();
        assertThrows(RuntimeException.class, () -> tickets.issue(7L, Instant.now().minusSeconds(1)));
        for (int i = 0; i < 8; i++) tickets.issue(7L, Instant.now().plusSeconds(60));
        assertThrows(RuntimeException.class, () -> tickets.issue(7L, Instant.now().plusSeconds(60)));
        assertDoesNotThrow(() -> tickets.issue(8L, Instant.now().plusSeconds(60)));
    }

    // 登录期限很短时，握手不能把过期登录续成长期连接。
    @Test
    void rejectsExpiredTicket() throws Exception {
        var tickets = new ApprovalHandshakeInterceptor();
        String ticket = tickets.issue(7L, Instant.now().plusMillis(100));
        Thread.sleep(150);
        var request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/agent/approvals/socket?ticket=" + ticket));
        assertFalse(tickets.beforeHandshake(request, mock(ServerHttpResponse.class), null, new HashMap<>()));
    }

    // 一个用户的两个标签页都收到变更，另一个用户只收到自己的连接确认。
    @Test
    void broadcastsOnlyToOwnersTabs() throws Exception {
        var handler = new ApprovalWebSocketHandler();
        var first = socket("a", 7L);
        var second = socket("b", 7L);
        var stranger = socket("c", 8L);
        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);
        handler.afterConnectionEstablished(stranger);
        clearInvocations(first, second, stranger);
        handler.notifyUser(7L);
        verify(first).sendMessage(argThat(message -> message.getPayload().toString().contains("APPROVALS_CHANGED")));
        verify(second).sendMessage(any());
        verify(stranger, never()).sendMessage(any());
    }

    // 已断开的连接和非法命令会被清理，不允许用 WebSocket 直接批准。
    @Test
    void rejectsCommandsAndCleansClosedOrExpiredSessions() throws Exception {
        var handler = new ApprovalWebSocketHandler();
        var command = socket("a", 7L);
        handler.afterConnectionEstablished(command);
        handler.handleMessage(command, new TextMessage("approve"));
        verify(command).close(CloseStatus.POLICY_VIOLATION);
        var expired = socket("b", 7L);
        handler.afterConnectionEstablished(expired);
        expired.getAttributes().put(ApprovalHandshakeInterceptor.AUTH_EXPIRES_AT, Instant.now().minusSeconds(1));
        handler.removeExpiredConnections();
        verify(expired).close(CloseStatus.POLICY_VIOLATION);
        var closed = socket("c", 7L);
        handler.afterConnectionEstablished(closed);
        handler.afterConnectionClosed(closed, CloseStatus.NORMAL);
        clearInvocations(command, expired, closed);
        handler.notifyUser(7L);
        verify(command, never()).sendMessage(any());
        verify(expired, never()).sendMessage(any());
        verify(closed, never()).sendMessage(any());
    }

    // 同一个用户不能无限开标签页耗尽服务端连接资源。
    @Test
    void limitsConnectionsPerUser() {
        var handler = new ApprovalWebSocketHandler();
        for (int i = 0; i < 8; i++) handler.afterConnectionEstablished(socket("s" + i, 7L));
        var extra = socket("extra", 7L);
        handler.afterConnectionEstablished(extra);
        assertDoesNotThrow(() -> verify(extra).close(CloseStatus.POLICY_VIOLATION));
    }

    // 发送失败不阻断其他连接，也不向业务层抛网络异常。
    @Test
    void isolatesBrokenConnection() throws Exception {
        var handler = new ApprovalWebSocketHandler();
        var broken = socket("a", 7L);
        var healthy = socket("b", 7L);
        handler.afterConnectionEstablished(broken);
        handler.afterConnectionEstablished(healthy);
        clearInvocations(healthy);
        doThrow(new IOException("测试断线")).when(broken).sendMessage(any());
        assertDoesNotThrow(() -> handler.notifyUser(7L));
        verify(healthy).sendMessage(any());
    }

    // 使用真实事务管理器触发回调；JDBC 连接是替身，不接触业务数据库。
    @Test
    void notifiesAfterCommitButNeverAfterRollback() throws Exception {
        var handler = mock(ApprovalWebSocketHandler.class);
        var notifier = new ApprovalNotifier(handler, Runnable::run);
        var transaction = transaction();
        transaction.executeWithoutResult(status -> {
            notifier.changedAfterCommit(7L);
            verifyNoInteractions(handler);
        });
        verify(handler).notifyUser(7L);
        clearInvocations(handler);
        transaction.executeWithoutResult(status -> {
            notifier.changedAfterCommit(7L);
            status.setRollbackOnly();
        });
        // 没有事务也不会悄悄提前推送。
        notifier.changedAfterCommit(7L);
        verifyNoInteractions(handler);
    }

    // 通知线程池已满时，已成功的审批事务仍正常完成。
    @Test
    void queueRejectionDoesNotFailCommittedApproval() throws Exception {
        var handler = mock(ApprovalWebSocketHandler.class);
        var notifier = new ApprovalNotifier(handler, task -> { throw new RejectedExecutionException(); });
        assertDoesNotThrow(() -> transaction().executeWithoutResult(status -> notifier.changedAfterCommit(7L)));
        verifyNoInteractions(handler);
    }

    // 建立可观察的连接替身，属性里只有服务器确认过的身份。
    private WebSocketSession socket(String id, Long userId) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ApprovalHandshakeInterceptor.USER_ID, userId);
        attributes.put(ApprovalHandshakeInterceptor.AUTH_EXPIRES_AT, Instant.now().plusSeconds(60));
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    // 让 Spring 真实执行提交、回滚和事务同步，不手工调用 afterCommit 冒充结果。
    private TransactionTemplate transaction() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }
}
