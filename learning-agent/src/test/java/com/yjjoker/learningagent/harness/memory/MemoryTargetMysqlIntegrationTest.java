package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.memory.impl.DatabaseStructuredMemoryService;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.MemoryCandidatePersistenceService;
import com.yjjoker.learningagent.repository.UserMemoryRepository;
import com.yjjoker.learningagent.repository.SessionMemoryRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import static com.yjjoker.learningagent.harness.memory.MemoryTestData.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryOperation.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryScope.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.argThat;

// 显式开启才连接 MySQL；所有写入只作用于当前连接的临时表。
@EnabledIfEnvironmentVariable(named = "MEMORY_MYSQL_TEST", matches = "true")
class MemoryTargetMysqlIntegrationTest {
    private Connection connection;
    private SingleConnectionDataSource dataSource;
    private UserMemoryRepository users;
    private SessionMemoryRepository sessions;
    private DatabaseStructuredMemoryService store;
    private MemoryCandidatePersistenceService persistence;

    // 创建连接级临时表，真实用户表在本连接中被遮蔽，其他连接不受影响。
    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:mysql://" + env("MYSQL_HOST", "localhost") + ":" + env("MYSQL_PORT", "3306")
                + "/" + env("MYSQL_DATABASE", "learning_agent") + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        connection = DriverManager.getConnection(url, env("MYSQL_USER", "root"), System.getenv("MYSQL_PASSWORD"));
        dataSource = new SingleConnectionDataSource(connection, true);
        try (Statement statement = connection.createStatement()) {
            // 两个临时表使用与 Repository 相同的字段，不复制真实业务数据。
            for (String table : List.of("user_memories", "session_memories")) {
                String ownerColumn = table.equals("user_memories") ? "user_id" : "session_id";
                statement.execute("CREATE TEMPORARY TABLE " + table + " (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                        + ownerColumn + " BIGINT NOT NULL, memory_key VARCHAR(128) NOT NULL, memory_topic VARCHAR(128) NOT NULL, "
                        + "memory_summary VARCHAR(1000) NOT NULL, memory_content LONGTEXT NOT NULL, status VARCHAR(20) NOT NULL, "
                        + "created_at DATETIME(6), updated_at DATETIME(6), UNIQUE KEY owner_key (" + ownerColumn + ", memory_key)) ENGINE=InnoDB");
            }
        }
        // 使用实际 MyBatis 映射和同一数据库连接，验证真实 SQL。
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(UserMemoryRepository.class);
        configuration.addMapper(SessionMemoryRepository.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        users = template.getMapper(UserMemoryRepository.class);
        sessions = template.getMapper(SessionMemoryRepository.class);
        store = new DatabaseStructuredMemoryService(users, sessions);
        persistence = transactionalService(store);
        // 第三条记录与最喜欢的运动无关，用来检查是否误改。
        store.saveUserMemory(user(1, "favoriteSport", "最喜欢羽毛球"));
        store.saveUserMemory(user(2, "userFavoriteSport", "最喜欢羽毛球"));
        store.saveUserMemory(user(3, "runningFrequency", "每周跑步三次"));
    }

    // 关闭连接即可自动清理临时表，不执行真实表的删除语句。
    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    // 真实 SQL 更新和软删除两条同义记录，第三条记录仍可召回。
    @Test
    void shouldUpdateThenDeleteBothAliasesInMysql() {
        String update = "现在最喜欢足球";
        var updateContext = snapshot();
        persistence.persist(updateContext, update, List.of(candidate(UPDATE, USER,
                refsByIds(updateContext, 1L, 2L), update, null, "最喜欢足球")));
        assertEquals("最喜欢足球", users.findActiveById(USER_ID, 1L).getMemoryContent());
        assertEquals("最喜欢足球", users.findActiveById(USER_ID, 2L).getMemoryContent());
        // 索引按更新时间排序，重新从 key 查引用，不能沿用上轮编号。
        var deleteContext = snapshot();
        var refs = deleteContext.getTargets().stream().filter(target -> !target.getMemoryKey().equals("runningFrequency"))
                .map(MemoryExtractionTarget::getMemoryRef).toList();
        persistence.persist(deleteContext, "忘记最喜欢的运动", List.of(
                candidate(DELETE, USER, refs, "忘记最喜欢的运动", null, null)));
        assertNull(users.findActiveById(USER_ID, 1L));
        assertNull(users.findActiveById(USER_ID, 2L));
        assertEquals("每周跑步三次", users.findActiveById(USER_ID, 3L).getMemoryContent());
        assertEquals(1, store.loadUserMemoryIndex(USER_ID).size());
        assertEquals(3, countRows());
    }

    // 第二条更新失败时，第一条已经执行的 SQL 也必须回滚。
    @Test
    void shouldRollbackFirstUpdateWhenSecondWriteFails() {
        UserMemoryRepository failing = spy(users);
        doThrow(new IllegalStateException("模拟第二条写入失败"))
                .when(failing).update(argThat(memory -> memory.getId().equals(2L)));
        var service = transactionalService(new DatabaseStructuredMemoryService(failing, sessions));
        var context = snapshot();
        var refs = refsByIds(context, 1L, 2L);
        assertThrows(IllegalStateException.class, () -> service.persist(context, "改成足球", List.of(
                candidate(UPDATE, USER, refs, "改成足球", null, "最喜欢足球"))));
        assertEquals("最喜欢羽毛球", users.findActiveById(USER_ID, 1L).getMemoryContent());
        assertEquals("最喜欢羽毛球", users.findActiveById(USER_ID, 2L).getMemoryContent());
    }

    // 第二条删除失败时，不允许只删除第一条同义记忆。
    @Test
    void shouldRollbackFirstDeleteWhenSecondWriteFails() {
        UserMemoryRepository failing = spy(users);
        doThrow(new IllegalStateException("模拟第二条删除失败")).when(failing)
                .softDelete(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any());
        var service = transactionalService(new DatabaseStructuredMemoryService(failing, sessions));
        var context = snapshot();
        assertThrows(IllegalStateException.class, () -> service.persist(context, "忘记运动", List.of(
                candidate(DELETE, USER, refsByIds(context, 1L, 2L), "忘记运动", null, null))));
        assertEquals(3, store.loadUserMemoryIndex(USER_ID).size());
    }

    // 会话表和长期表可以有相同 ID，但两类操作不能串表。
    @Test
    void shouldKeepSessionAndUserChangesSeparate() {
        store.saveSessionMemory(session(1, "goal", "学 Java"));
        store.saveSessionMemory(session(2, "currentGoal", "学 Java"));
        var context = snapshot();
        var refs = context.getTargets().stream().filter(target -> target.getScope() == SESSION)
                .map(MemoryExtractionTarget::getMemoryRef).toList();
        persistence.persist(context, "当前改学网络", List.of(candidate(UPDATE, SESSION, refs, "改学网络", null, "学网络")));
        assertEquals("学网络", sessions.findActiveById(SESSION_ID, 1L).getMemoryContent());
        assertEquals("学网络", sessions.findActiveById(SESSION_ID, 2L).getMemoryContent());
        assertEquals("最喜欢羽毛球", users.findActiveById(USER_ID, 1L).getMemoryContent());
    }

    // 用 Spring 实际事务拦截器执行 @Transactional，验证提交和回滚。
    private MemoryCandidatePersistenceService transactionalService(DatabaseStructuredMemoryService targetStore) {
        ProxyFactory proxy = new ProxyFactory(new MemoryCandidatePersistenceService(targetStore));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (MemoryCandidatePersistenceService) proxy.getProxy();
    }

    // 每次操作前从数据库读取最新索引并重新建立映射。
    private MemoryExtractionContext snapshot() {
        return context(store.loadUserMemoryIndex(USER_ID), store.loadSessionMemoryIndex(SESSION_ID));
    }

    // 测试按真实目标构造引用，不依赖 SQL 的返回顺序。
    private List<String> refsByIds(MemoryExtractionContext context, Long... ids) {
        return List.of(ids).stream().map(id -> context.getTargets().stream()
                .filter(target -> target.getScope() == USER && target.getMemoryId().equals(id))
                .findFirst().orElseThrow().getMemoryRef()).toList();
    }

    // 软删除后行数不变，验证没有物理删除数据库记录。
    private int countRows() {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM user_memories")) {
            result.next();
            return result.getInt(1);
        } catch (Exception exception) {
            throw new IllegalStateException("读取临时表行数失败", exception);
        }
    }

    // 读取测试运行环境，不把数据库凭证写进源代码或日志。
    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
