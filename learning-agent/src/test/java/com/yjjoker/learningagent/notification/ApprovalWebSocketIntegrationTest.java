package com.yjjoker.learningagent.notification;

import com.yjjoker.learningagent.config.ApprovalWebSocketConfig;
import com.yjjoker.learningagent.config.JwtProperties;
import com.yjjoker.learningagent.config.WebMvcConfig;
import com.yjjoker.learningagent.controller.ApprovalNotificationController;
import com.yjjoker.learningagent.controller.MemoryApprovalController;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.handler.ServiceExceptionHandler;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.*;
import com.yjjoker.learningagent.interceptor.LoginInterceptor;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.repository.MemoryApprovalRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.utils.JwtService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.sql.Connection;
import javax.sql.DataSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 启动随机端口的真实 HTTP/WebSocket 服务；不加载业务数据库、后台任务或 Aliyun 客户端。
@SpringBootTest(classes = ApprovalWebSocketIntegrationTest.TestServer.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=approval-websocket-test", "spring.main.banner-mode=off"})
class ApprovalWebSocketIntegrationTest {
    @Autowired private Environment environment;
    @Autowired private JwtService jwt;
    @Autowired private ApprovalWebSocketHandler handler;
    @Autowired private MemoryApprovalService approvals;
    @Autowired private MemoryApprovalRepository repository;
    @Autowired private LearningSessionRepository sessions;
    @Autowired private PlatformTransactionManager transactions;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    // 走真实 JWT -> HTTP 领票 -> WebSocket 握手 -> 推送 -> 心跳 -> 重连链路。
    @Test
    void realSocketReceivesOwnerNotificationAndCanReconnect() throws Exception {
        var first = new Probe();
        var second = new Probe();
        var stranger = new Probe();
        WebSocket a = connect(ticket(7L), first, "http://localhost:5173");
        WebSocket b = connect(ticket(7L), second, "http://localhost:5173");
        WebSocket c = connect(ticket(8L), stranger, "http://localhost:5173");
        try {
            assertTrue(first.take().contains("READY"));
            assertTrue(second.take().contains("READY"));
            assertTrue(stranger.take().contains("READY"));
            handler.notifyUser(7L);
            assertTrue(first.take().contains("APPROVALS_CHANGED"));
            assertTrue(second.take().contains("APPROVALS_CHANGED"));
            assertNull(stranger.messages.poll(300, TimeUnit.MILLISECONDS));
            a.sendText("ping", true).join();
            assertTrue(first.take().contains("PONG"));
            a.sendClose(WebSocket.NORMAL_CLOSURE, "test").join();
            var reconnected = new Probe();
            WebSocket next = connect(ticket(7L), reconnected, "http://localhost:5173");
            // READY 告诉前端重新通过 HTTP 读取审批，不依赖断线期间的消息重放。
            try { assertTrue(reconnected.take().contains("READY")); }
            finally { next.abort(); }
        } finally { a.abort(); b.abort(); c.abort(); }
    }

    // 缺失票据、重复票据和非允许网页来源都不能建立已认证连接。
    @Test
    void rejectsMissingReusedTicketAndUntrustedOrigin() throws Exception {
        assertThrows(Exception.class, () -> connect("invalid", new Probe(), "http://localhost:5173"));
        String oneUse = ticket(7L);
        var probe = new Probe();
        WebSocket socket = connect(oneUse, probe, "http://localhost:5173");
        try {
            assertTrue(probe.take().contains("READY"));
            assertThrows(Exception.class, () -> connect(oneUse, new Probe(), "http://localhost:5173"));
            assertThrows(Exception.class, () -> connect(ticket(7L), new Probe(), "https://untrusted.example"));
        } finally { socket.abort(); }
    }

    // 领票入口仍经过原登录拦截器，未登录请求不能拿到票据。
    @Test
    void ticketEndpointRejectsAnonymousRequests() throws Exception {
        var response = client.send(HttpRequest.newBuilder(http("/agent/approvals/socket-ticket"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertFalse(response.statusCode() == 200 && response.body().contains("\"ticket\""));
    }

    // 真实业务服务创建申请，HTTP 拒绝后再次通知；仓库替身不会污染业务数据库。
    @Test
    void approvalCreationAndHttpRejectionNotifyAfterTransaction() throws Exception {
        prepareRepositories();
        var probe = new Probe();
        WebSocket socket = connect(ticket(7L), probe, "http://localhost:5173");
        try {
            assertTrue(probe.take().contains("READY"));
            MemoryApprovalRequest saved = createApproval();
            assertTrue(probe.take().contains("APPROVALS_CHANGED"));
            when(repository.findById(99L)).thenReturn(saved);
            when(repository.lock(99L, 7L)).thenReturn(saved);
            when(repository.findPendingByUserId(7L)).thenReturn(List.of(saved));
            var pending = client.send(HttpRequest.newBuilder(http("/agent/memory-approvals"))
                    .header("Authorization", "Bearer " + jwt.createToken(7L, UserRoleEnum.USER)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(pending.body().contains("PENDING"));
            var rejected = client.send(HttpRequest.newBuilder(http("/agent/memory-approvals/99/reject"))
                    .header("Authorization", "Bearer " + jwt.createToken(7L, UserRoleEnum.USER))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rejected.statusCode());
            assertTrue(rejected.body().contains("REJECTED"));
            assertTrue(probe.take().contains("APPROVALS_CHANGED"));
        } finally { socket.abort(); }
    }

    // 外层事务撤销创建申请时，连接上不能出现“申请已产生”的通知。
    @Test
    void rolledBackApprovalDoesNotReachSocket() throws Exception {
        prepareRepositories();
        var probe = new Probe();
        WebSocket socket = connect(ticket(7L), probe, "http://localhost:5173");
        try {
            assertTrue(probe.take().contains("READY"));
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                createApproval();
                status.setRollbackOnly();
            });
            assertNull(probe.messages.poll(500, TimeUnit.MILLISECONDS));
        } finally { socket.abort(); }
    }

    // 只模拟数据库返回；服务、事务代理、身份校验、HTTP 和 WebSocket 都使用实际代码。
    private void prepareRepositories() {
        reset(repository, sessions);
        LearningSession session = new LearningSession();
        session.setId(9L);
        session.setUserId(7L);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(9L)).thenReturn(Optional.of(session));
        when(repository.insert(any())).thenAnswer(call -> {
            call.<MemoryApprovalRequest>getArgument(0).setId(99L);
            return 1;
        });
        when(repository.decide(anyLong(), anyLong(), any(), anyString(), any(), any())).thenReturn(1);
    }

    // 提取入口通常由 Harness 调用；此处跳过模型，只提供确定性的测试候选。
    private MemoryApprovalRequest createApproval() {
        BaseContext.setCurrentId(7L);
        try {
            MemoryCandidate candidate = new MemoryCandidate();
            candidate.setOperation(MemoryOperation.CREATE);
            candidate.setScope(MemoryScope.USER);
            candidate.setMemoryKey("socket-test");
            candidate.setMemoryContent("测试审批通知");
            candidate.setUserEvidence("请记住测试内容");
            return approvals.create(7L, 9L, candidate, new MemoryExtractionContext(7L, 9L, List.of()));
        } finally { BaseContext.removeCurrentId(); }
    }

    // 使用真实 JWT 验签，不在请求参数里传 userId，也不输出令牌。
    private String ticket(Long userId) throws Exception {
        var response = client.send(HttpRequest.newBuilder(http("/agent/approvals/socket-ticket"))
                .header("Authorization", "Bearer " + jwt.createToken(userId, UserRoleEnum.USER))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        return new JsonMapper().readTree(response.body()).path("data").path("ticket").asString();
    }

    // 真实网络握手有超时，失败测试不能无限挂起。
    private WebSocket connect(String ticket, Probe probe, String origin) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + environment.getProperty("local.server.port")
                + "/agent/approvals/socket?ticket=" + ticket);
        return client.newWebSocketBuilder().header("Origin", origin).connectTimeout(Duration.ofSeconds(3))
                .buildAsync(uri, probe).get(5, TimeUnit.SECONDS);
    }

    // 测试端口由容器分配，不占用用户已经启动的 8080 服务。
    private URI http(String path) {
        return URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path);
    }

    // 这个内部类只是测试用的接收器，不进入生产代码。
    private static class Probe implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder fragments = new StringBuilder();

        // 允许底层交付第一帧，后续每次读取再申请下一帧。
        @Override
        public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        // 合并分片后再判断 JSON 消息，避免把一个通知误当成两条。
        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) { messages.add(fragments.toString()); fragments.setLength(0); }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        // 有界等待真实服务器通知；超时直接使断言失败。
        String take() throws InterruptedException {
            String value = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(value, "未收到预期的 WebSocket 消息");
            return value;
        }
    }

    // 仅装配这次通知功能需要的类，不扫描整个业务应用。
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @EnableTransactionManagement
    @Import({ApprovalWebSocketConfig.class, ApprovalWebSocketHandler.class, ApprovalHandshakeInterceptor.class,
            ApprovalNotificationController.class, LoginInterceptor.class, WebMvcConfig.class, ApprovalNotifier.class,
            MemoryApprovalService.class, MemoryApprovalController.class, ServiceExceptionHandler.class})
    static class TestServer {
        // 独立测试密钥只用于本进程测试，不读取或修改项目的真实 JWT 配置。
        @Bean
        JwtService jwtService() {
            return new JwtService(new JwtProperties(Base64.getEncoder().encodeToString(new byte[32]), 60000));
        }

        // 只替换数据库访问，不在测试服务中打开真实业务库。
        @Bean
        MemoryApprovalRepository approvals() { return mock(MemoryApprovalRepository.class); }

        // 会话归属由测试数据控制，仍由正式服务校验。
        @Bean
        LearningSessionRepository sessions() { return mock(LearningSessionRepository.class); }

        // 本测试拒绝提案，不执行真实记忆写入。
        @Bean
        MemoryCandidatePersistenceService persistence() { return mock(MemoryCandidatePersistenceService.class); }

        // 整理语义的完整验证已有专门测试，这里只验证通知链路。
        @Bean
        MemoryConsolidationApprovalService consolidation() { return mock(MemoryConsolidationApprovalService.class); }

        // 防止测试批准行为意外启动实际模型整理任务。
        @Bean
        MemoryConsolidationScheduler scheduler() { return mock(MemoryConsolidationScheduler.class); }

        // 真实事务管理器驱动 afterCommit，连接替身只模拟 JDBC 提交和回滚。
        @Bean
        PlatformTransactionManager transactions() throws Exception {
            DataSource source = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            return new DataSourceTransactionManager(source);
        }
    }
}
