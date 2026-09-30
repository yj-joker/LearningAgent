package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.harness.memory.impl.DatabaseStructuredMemoryService;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationPersistenceService;
import com.yjjoker.learningagent.harness.memory.service.StructuredMemoryService;
import com.yjjoker.learningagent.repository.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yjjoker.learningagent.harness.memory.MemoryTestData.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryScope.USER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 多连接不能共享连接级临时表，所以每个测试只创建并清理自己命名的独立测试库。
@EnabledIfEnvironmentVariable(named = "MEMORY_MYSQL_TEST", matches = "true")
@Slf4j
class MemoryConsolidationConcurrencyMysqlTest {
    private String schema;
    private boolean schemaCreated;
    private DriverManagerDataSource dataSource;
    private DatabaseStructuredMemoryService store;
    private MemoryConsolidationRepository progress;

    // 测试库使用随机名字；不复用、不清空用户正在使用的数据库。
    @BeforeEach
    void setUp() throws Exception {
        schema = "harness_mem_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quotedTestSchema() + " CHARACTER SET utf8mb4");
            schemaCreated = true;
        }
        dataSource = new DriverManagerDataSource(url(schema), env("MYSQL_USER", "root"), System.getenv("MYSQL_PASSWORD"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(new ClassPathResource("db/migration/memoryConsolidation.sql")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            // 仅创建当前测试使用的两张空记忆表，不复制真实用户资料。
            for (String table : List.of("user_memories", "session_memories")) {
                String owner = table.equals("user_memories") ? "user_id" : "session_id";
                statement.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                        + owner + " BIGINT NOT NULL, memory_key VARCHAR(128) NOT NULL, memory_topic VARCHAR(128) NOT NULL, "
                        + "memory_summary VARCHAR(1000) NOT NULL, memory_content LONGTEXT NOT NULL, status VARCHAR(20) NOT NULL, "
                        + "created_at DATETIME(6), updated_at DATETIME(6), UNIQUE KEY owner_key (" + owner + ", memory_key)) ENGINE=InnoDB");
            }
        }
        // 不使用单连接数据源，让两个线程的事务真正占用不同的数据库连接。
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(UserMemoryRepository.class);
        configuration.addMapper(SessionMemoryRepository.class);
        configuration.addMapper(MemoryConsolidationRepository.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var template = new SqlSessionTemplate(factory.getObject());
        progress = template.getMapper(MemoryConsolidationRepository.class);
        store = new DatabaseStructuredMemoryService(template.getMapper(UserMemoryRepository.class),
                template.getMapper(SessionMemoryRepository.class));
        store.saveUserMemory(user(1, "sport", "最喜欢羽毛球"));
        store.saveUserMemory(user(2, "aliasSport", "最喜欢羽毛球"));
        progress.initialize(USER, USER_ID, 20);
        log.info("并发测试库已创建，schema={}，合成记录数=2", schema);
    }

    // 只删除本次成功创建且名称符合约束的测试库，避免触及任何业务库。
    @AfterEach
    void tearDown() throws Exception {
        if (schemaCreated) {
            try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + quotedTestSchema());
                log.info("并发测试库已清理，schema={}", schema);
            }
        }
    }

    // A 持锁时 B 等待；A 提交后 B 发现进度已完成，不能再次提交。
    @Test
    void shouldSerializeConcurrentDuplicateInspections() throws Exception {
        var snapshot = snapshot();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var blockingStore = spy(store);
        doAnswer(invocation -> {
            Object memory = invocation.callRealMethod();
            locked.countDown();
            await(release);
            return memory;
        }).when(blockingStore).lockUserMemory(USER_ID, 1L);
        var firstWriter = writer(blockingStore);
        var secondWriter = writer(store);
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<Boolean> first = executor.submit(() -> firstWriter.persist(snapshot, new MemoryConsolidationPlan()));
                await(locked);
                Future<Boolean> second = executor.submit(() -> {
                    started.countDown();
                    return secondWriter.persist(snapshot, new MemoryConsolidationPlan());
                });
                await(started);
                // A 未释放锁时，B 不能完成保存；超时仅用于断言，不取消 B。
                assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
                release.countDown();
                assertTrue(first.get(10, TimeUnit.SECONDS));
                assertFalse(second.get(10, TimeUnit.SECONDS));
                assertEquals(20, progress.find(USER, USER_ID).getProcessedCount());
                assertEquals(2, store.loadUserMemoryIndex(USER_ID).size());
                log.info("真实双连接重复提交校验通过：一次提交、一次跳过");
            } finally {
                // 断言失败也释放测试闸门，不让线程长期持锁。
                release.countDown();
            }
        }
    }

    // B 等待 A 的新事实提交后，锁定读取必须看见新值并拒绝旧快照。
    @Test
    void shouldRejectSnapshotAfterWaitingForConcurrentUpdate() throws Exception {
        var oldSnapshot = snapshot();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var writer = writer(store);
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> update = executor.submit(() -> transaction.executeWithoutResult(status -> {
                    var memory = store.lockUserMemory(USER_ID, 1L);
                    memory.setMemoryContent("现在最喜欢足球");
                    store.updateUserMemory(memory);
                    progress.addChanges(USER, USER_ID, 1);
                    locked.countDown();
                    await(release);
                }));
                await(locked);
                Future<Boolean> stale = executor.submit(() -> {
                    started.countDown();
                    return writer.persist(oldSnapshot, new MemoryConsolidationPlan());
                });
                await(started);
                assertThrows(TimeoutException.class, () -> stale.get(250, TimeUnit.MILLISECONDS));
                release.countDown();
                update.get(10, TimeUnit.SECONDS);
                // 等锁后读取到新版本，整份旧方案返回未执行，让审批层记录 STALE。
                assertFalse(stale.get(10, TimeUnit.SECONDS));
                assertEquals("现在最喜欢足球", store.recallUserMemory(USER_ID, 1L).getMemoryContent());
                assertEquals(21, progress.find(USER, USER_ID).getChangeCount());
                assertEquals(0, progress.find(USER, USER_ID).getProcessedCount());
                log.info("真实双连接旧快照校验通过：等待后拒绝旧方案，新事实保留");
            } finally {
                release.countDown();
            }
        }
    }

    // 对照实验故意反向取锁；仅验证 MySQL 死锁机制，不伪称业务代码主动制造死锁。
    @Test
    void shouldDetectDeadlockWithOppositeLockOrder() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT @@innodb_deadlock_detect")) {
            result.next();
            Assumptions.assumeTrue(result.getBoolean(1), "数据库未启用死锁检测，跳过机制对照实验");
        }
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> reverseOrderTransaction(1, 2, barrier));
            Future<Integer> second = executor.submit(() -> reverseOrderTransaction(2, 1, barrier));
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(code -> code == 1213).count());
            assertEquals(1, results.stream().filter(code -> code == 0).count());
            log.info("真实死锁对照实验通过：一方收到 MySQL 1213，一方继续完成");
        }
    }

    // 两个连接各拿一把锁后再申请对方的锁，用屏障固定交错而不是依赖随机时序。
    private int reverseOrderTransaction(long first, long second, CyclicBarrier barrier) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockRecord(connection, first);
                barrier.await(5, TimeUnit.SECONDS);
                lockRecord(connection, second);
                connection.commit();
                return 0;
            } catch (SQLException exception) {
                connection.rollback();
                return exception.getErrorCode();
            }
        }
    }

    // 查询结果读取完也不释放行锁，锁随所属事务结束释放。
    private void lockRecord(Connection connection, long id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM user_memories WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
            }
        }
    }

    // 生产保存类经过真实 Spring 事务代理，两个线程不会共用一个事务。
    private MemoryConsolidationPersistenceService writer(StructuredMemoryService memoryService) {
        ProxyFactory proxy = new ProxyFactory(new MemoryConsolidationPersistenceService(memoryService, progress));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return (MemoryConsolidationPersistenceService) proxy.getProxy();
    }

    // 从已提交记录建立本次快照，让两个请求持有同一份旧依据。
    private MemoryConsolidationSnapshot snapshot() {
        var entries = List.of(1L, 2L).stream().map(id -> {
            var memory = store.recallUserMemory(USER_ID, id);
            return new MemoryConsolidationEntry("memory_" + id, id, memory.getMemoryKey(), memory.getMemoryTopic(),
                    memory.getMemorySummary(), memory.getMemoryContent(), memory.getUpdatedAt());
        }).toList();
        return new MemoryConsolidationSnapshot(progress.find(USER, USER_ID), entries);
    }

    // 所有测试等待都有上限；收到中断后保留中断标记并终止。
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发测试等待超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", exception);
        }
    }

    // 校验完整数据库名，仅允许清理本次随机创建的测试库。
    private String quotedTestSchema() {
        if (schema == null || !schema.matches("harness_mem_it_[0-9a-f]{32}")
                || schema.equals(env("MYSQL_DATABASE", "learning_agent"))) {
            throw new IllegalStateException("拒绝操作非测试数据库");
        }
        return "`" + schema + "`";
    }

    // 管理连接只用于创建和删除当前测试库，不读取业务记忆。
    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(url(env("MYSQL_DATABASE", "learning_agent")),
                env("MYSQL_USER", "root"), System.getenv("MYSQL_PASSWORD"));
    }

    // 连接和网络等待都有上限，失败不会无限占用测试线程。
    private String url(String database) {
        return "jdbc:mysql://" + env("MYSQL_HOST", "localhost") + ":" + env("MYSQL_PORT", "3306") + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=20000";
    }

    // 配置来自进程环境，不在测试日志中输出凭证。
    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
