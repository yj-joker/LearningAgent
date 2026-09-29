package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.config.MemoryConsolidationProperties;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.memory.impl.LlmMemoryConsolidator;
import com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationService;
import com.yjjoker.learningagent.harness.memory.service.MemoryConsolidator;
import lombok.extern.slf4j.Slf4j;
import com.yjjoker.learningagent.harness.memory.impl.DatabaseStructuredMemoryService;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.MemoryCandidatePersistenceService;
import com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationPersistenceService;
import com.yjjoker.learningagent.repository.MemoryConsolidationRepository;
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
@Slf4j
class MemoryTargetMysqlIntegrationTest {
    private Connection connection;
    private SingleConnectionDataSource dataSource;
    private UserMemoryRepository users;
    private SessionMemoryRepository sessions;
    private MemoryConsolidationRepository progress;
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
            // 从统一建表文件创建临时进度表，不修改真实进度，也不依赖旧脚本。
            String progressDdl = tableDdl("memory_consolidation_state");
            statement.execute(progressDdl.replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMPORARY TABLE"));
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
        configuration.addMapper(MemoryConsolidationRepository.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        users = template.getMapper(UserMemoryRepository.class);
        sessions = template.getMapper(SessionMemoryRepository.class);
        progress = template.getMapper(MemoryConsolidationRepository.class);
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

    // 真实 COUNT 按归属隔离并排除软删除记录，预检查本身不初始化整理进度。
    @Test
    void shouldCountActiveMemoriesWithoutInitializingProgress() {
        assertEquals(3, progress.countActiveUserMemories(USER_ID));
        assertEquals(0, progress.countActiveUserMemories(USER_ID + 1));
        store.deleteUserMemory(USER_ID, 2L);
        assertEquals(2, progress.countActiveUserMemories(USER_ID));
        store.saveSessionMemory(session(1, "goal", "学习 Java"));
        assertEquals(1, progress.countActiveSessionMemories(SESSION_ID));
        assertEquals(0, progress.countActiveSessionMemories(SESSION_ID + 1));
        assertNull(progress.find(USER, USER_ID));
        assertNull(progress.find(SESSION, SESSION_ID));
    }

    // 不调用模型也能验证完整审批表定义，避免删除旧脚本后仅靠编译判断 SQL 正确。
    @Test
    void shouldCreateApprovalTableFromUnifiedSchema() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(tableDdl("memory_approval_requests")
                    .replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMPORARY TABLE"));
            // 读取 MySQL 实际建立的列和索引，而不是只在源码字符串中寻找字段。
            var columns = new java.util.HashSet<String>();
            try (ResultSet result = statement.executeQuery("SHOW COLUMNS FROM memory_approval_requests")) {
                while (result.next()) {
                    columns.add(result.getString("Field"));
                }
            }
            assertTrue(columns.containsAll(List.of("approval_type", "snapshot_change_count",
                    "snapshot_processed_count", "pending_consolidation_key", "consolidation_owner_id")));
            var indexes = new java.util.HashSet<String>();
            try (ResultSet result = statement.executeQuery("SHOW INDEX FROM memory_approval_requests")) {
                while (result.next()) {
                    indexes.add(result.getString("Key_name"));
                }
            }
            assertTrue(indexes.containsAll(List.of("uk_pending_consolidation", "uk_consolidation_version")));
        }
        log.info("统一建表文件验收通过：审批字段和两个防重复索引已在MySQL临时表建立");
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
        ProxyFactory proxy = new ProxyFactory(new MemoryCandidatePersistenceService(targetStore, progress));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (MemoryCandidatePersistenceService) proxy.getProxy();
    }

    // 真实合并后只少一条有效记录，原行保留，整理计数也随同提交。
    @Test
    void shouldCommitConsolidationAndProgressTogether() {
        progress.initialize(USER, USER_ID, 20);
        var snapshot = consolidationSnapshot();
        assertTrue(consolidationWriter(store).persist(snapshot, consolidationPlan()));
        assertEquals("最喜欢羽毛球", users.findActiveById(USER_ID, 1L).getMemoryContent());
        assertNull(users.findActiveById(USER_ID, 2L));
        assertEquals("每周跑步三次", users.findActiveById(USER_ID, 3L).getMemoryContent());
        assertEquals(3, countRows());
        assertEquals(20, progress.find(USER, USER_ID).getProcessedCount());
        assertEquals(20, progress.find(USER, USER_ID).getChangeCount());
    }

    // 更新成功但删除失败时，正文、删除状态和进度必须一起回滚。
    @Test
    void shouldRollbackConsolidationWhenDeleteFails() {
        progress.initialize(USER, USER_ID, 20);
        var snapshot = consolidationSnapshot();
        var failingStore = spy(store);
        doThrow(new IllegalStateException("模拟软删除失败")).when(failingStore).deleteUserMemory(USER_ID, 2L);
        var plan = consolidationPlan();
        plan.getMerges().getFirst().setMemoryContent("这个修改必须被事务回滚");
        assertThrows(IllegalStateException.class, () -> consolidationWriter(failingStore).persist(snapshot, plan));
        assertEquals("最喜欢羽毛球", users.findActiveById(USER_ID, 1L).getMemoryContent());
        assertNotNull(users.findActiveById(USER_ID, 2L));
        assertEquals(0, progress.find(USER, USER_ID).getProcessedCount());
    }

    // 正常提取实际改了两条记录，应累计两次而不是按一个候选计数。
    @Test
    void shouldPersistNormalChangeCountAndKeepInitializationIdempotent() {
        progress.initialize(USER, USER_ID, 3);
        var context = snapshot();
        persistence.persist(context, "改成足球", List.of(candidate(UPDATE, USER,
                refsByIds(context, 1L, 2L), "改成足球", null, "最喜欢足球")));
        assertEquals(5, progress.find(USER, USER_ID).getChangeCount());
        progress.initialize(USER, USER_ID, 999);
        assertEquals(5, progress.find(USER, USER_ID).getChangeCount());
    }

    // 进度更新失败时，正常记忆修改也不能留下半批结果。
    @Test
    void shouldRollbackNormalChangesWhenProgressWriteFails() {
        progress.initialize(USER, USER_ID, 3);
        var failingProgress = spy(progress);
        doThrow(new IllegalStateException("模拟进度写入失败")).when(failingProgress).addChanges(USER, USER_ID, 2);
        ProxyFactory proxy = new ProxyFactory(new MemoryCandidatePersistenceService(store, failingProgress));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        var service = (MemoryCandidatePersistenceService) proxy.getProxy();
        var context = snapshot();
        assertThrows(IllegalStateException.class, () -> service.persist(context, "改成足球", List.of(
                candidate(UPDATE, USER, refsByIds(context, 1L, 2L), "改成足球", null, "最喜欢足球"))));
        assertEquals("最喜欢羽毛球", users.findActiveById(USER_ID, 1L).getMemoryContent());
        assertEquals("最喜欢羽毛球", users.findActiveById(USER_ID, 2L).getMemoryContent());
        assertEquals(3, progress.find(USER, USER_ID).getChangeCount());
    }

    // 新事实已经提交后，旧整理快照必须失败，不能把足球覆盖回羽毛球。
    @Test
    void shouldRejectStaleSnapshotAndPreserveLatestCommittedFact() {
        progress.initialize(USER, USER_ID, 20);
        var oldSnapshot = consolidationSnapshot();
        var changed = store.recallUserMemory(USER_ID, 2L);
        changed.setMemoryContent("现在最喜欢足球");
        store.updateUserMemory(changed);
        progress.addChanges(USER, USER_ID, 1);
        // 数据变化返回未执行，审批层据此保存 STALE，而不是把状态更新一起回滚。
        assertFalse(consolidationWriter(store).persist(oldSnapshot, consolidationPlan()));
        assertEquals("现在最喜欢足球", users.findActiveById(USER_ID, 2L).getMemoryContent());
        assertEquals(21, progress.find(USER, USER_ID).getChangeCount());
        assertEquals(0, progress.find(USER, USER_ID).getProcessedCount());
    }

    // 没有合并项时也要阻止同一轮重复提交，不能只依靠正文是否变化。
    @Test
    void shouldRejectDuplicateCompletedInspection() {
        progress.initialize(USER, USER_ID, 20);
        var snapshot = consolidationSnapshot();
        var writer = consolidationWriter(store);
        assertTrue(writer.persist(snapshot, new MemoryConsolidationPlan()));
        assertFalse(writer.persist(snapshot, new MemoryConsolidationPlan()));
        assertEquals(20, progress.find(USER, USER_ID).getProcessedCount());
        assertEquals(3, store.loadUserMemoryIndex(USER_ID).size());
    }

    // 使用真实条件 SQL，确认不能把期间新发生的变更一并标成已整理。
    @Test
    void shouldRejectOutdatedConditionalProgressUpdate() {
        progress.initialize(USER, USER_ID, 20);
        progress.addChanges(USER, USER_ID, 1);
        assertEquals(0, progress.markProcessed(USER, USER_ID, 20, 0));
        assertEquals(21, progress.find(USER, USER_ID).getChangeCount());
        assertEquals(0, progress.find(USER, USER_ID).getProcessedCount());
    }

    // 注入最后一步更新零行，确认已执行的合并和软删除全部回滚。
    @Test
    void shouldRollbackMergeWhenProgressUpdateAffectsNoRow() {
        progress.initialize(USER, USER_ID, 20);
        var failingProgress = spy(progress);
        doReturn(0).when(failingProgress).markProcessed(USER, USER_ID, 20, 0);
        ProxyFactory proxy = new ProxyFactory(new MemoryConsolidationPersistenceService(store, failingProgress));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        var writer = (MemoryConsolidationPersistenceService) proxy.getProxy();
        var snapshot = consolidationSnapshot();
        var plan = consolidationPlan();
        plan.getMerges().getFirst().setMemoryContent("这次写入必须回滚");
        assertThrows(IllegalStateException.class, () -> writer.persist(snapshot, plan));
        assertEquals("最喜欢羽毛球", users.findActiveById(USER_ID, 1L).getMemoryContent());
        assertNotNull(users.findActiveById(USER_ID, 2L));
        assertEquals(0, progress.find(USER, USER_ID).getProcessedCount());
    }

    // 真实模型方案经过阈值判断、事务保存与再次召回，数据只落在临时表。
    @Test
    @EnabledIfEnvironmentVariable(named = "MEMORY_ALIYUN_TEST", matches = "true")
    void shouldPersistRealAliyunPlanAndRecallMergedDetails() {
        var first = store.recallUserMemory(USER_ID, 1L);
        first.setMemoryContent("用户最喜欢羽毛球，每周六去体育馆打球。");
        store.updateUserMemory(first);
        var second = store.recallUserMemory(USER_ID, 2L);
        second.setMemoryContent("用户最喜欢的运动是羽毛球，通常和同事一起打球。");
        store.updateUserMemory(second);
        progress.initialize(USER, USER_ID, 20);
        progress.initialize(SESSION, SESSION_ID, 0);
        var properties = new MemoryConsolidationProperties();
        MemoryConsolidator model = spy(new LlmMemoryConsolidator(MemoryTargetAliyunTest.client(), new LlmRetryExecutor(), properties));
        // 此集成测试只验证模型和整理写入器，先捕获提案，再显式模拟批准；不覆盖审批 HTTP。
        var proposals = mock(com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationApprovalService.class);
        var coordinator = new MemoryConsolidationService(properties, progress, store, model, consolidationWriter(store), proposals);
        coordinator.consolidateIfNeeded(USER_ID, SESSION_ID);
        assertEquals(3, store.loadUserMemoryIndex(USER_ID).size(), "未批准前不能合并");
        var snapshotArg = org.mockito.ArgumentCaptor.forClass(MemoryConsolidationSnapshot.class);
        var planArg = org.mockito.ArgumentCaptor.forClass(MemoryConsolidationPlan.class);
        verify(proposals).submit(eq(USER_ID), eq(SESSION_ID), snapshotArg.capture(), planArg.capture());
        assertTrue(consolidationWriter(store).persist(snapshotArg.getValue(), planArg.getValue()));

        // 不假定模型一定保留哪条 ID，但保留项必须来自原来的两个来源。
        var index = store.loadUserMemoryIndex(USER_ID);
        assertEquals(2, index.size());
        var kept = index.stream().filter(memory -> memory.getId() != 3L).findFirst().orElseThrow();
        String content = store.recallUserMemory(USER_ID, kept.getId()).getMemoryContent();
        for (String detail : List.of("羽毛球", "周六", "体育馆", "同事")) {
            assertTrue(content.contains(detail), "召回正文应保留：" + detail);
        }
        assertEquals(kept.getId() == 1L ? "favoriteSport" : "userFavoriteSport", kept.getMemoryKey());
        long removedId = kept.getId() == 1L ? 2L : 1L;
        assertNull(users.findActiveById(USER_ID, removedId));
        assertThrows(com.yjjoker.learningagent.exception.NotFountException.class,
                () -> store.recallUserMemory(USER_ID, removedId));
        assertEquals("每周跑步三次", users.findActiveById(USER_ID, 3L).getMemoryContent());
        assertEquals(3, countRows());
        assertEquals(20, progress.find(USER, USER_ID).getProcessedCount());
        // 完成后再次检查不应立即重复调用模型。
        coordinator.consolidateIfNeeded(USER_ID, SESSION_ID);
        verify(model, times(1)).consolidate(any());
        log.info("真实模型与 MySQL 集成通过，activeCount={}，keptId={}，deletedId={}，processedCount=20",
                index.size(), kept.getId(), removedId);
    }

    // 真实模型生成后注入另一个已提交的新事实，检查保存入口拒绝旧依据。
    @Test
    @EnabledIfEnvironmentVariable(named = "MEMORY_ALIYUN_TEST", matches = "true")
    void shouldRejectRealAliyunPlanWhenMemoryChangesBeforeSave() {
        progress.initialize(USER, USER_ID, 20);
        var snapshot = consolidationSnapshot();
        var model = new LlmMemoryConsolidator(MemoryTargetAliyunTest.client(), new LlmRetryExecutor(), new MemoryConsolidationProperties());
        var plan = model.consolidate(snapshot);
        assertEquals(1, plan.getMerges().size(), "模型应识别两条羽毛球同义记忆");
        var changed = store.recallUserMemory(USER_ID, 2L);
        changed.setMemoryContent("现在最喜欢足球");
        store.updateUserMemory(changed);
        progress.addChanges(USER, USER_ID, 1);
        assertFalse(consolidationWriter(store).persist(snapshot, plan));
        assertEquals("现在最喜欢足球", users.findActiveById(USER_ID, 2L).getMemoryContent());
        assertEquals(3, store.loadUserMemoryIndex(USER_ID).size());
        assertEquals(0, progress.find(USER, USER_ID).getProcessedCount());
        log.info("真实模型与 MySQL 旧快照校验通过：新事实保留，旧合并方案未提交");
    }

    // 创建真实事务代理，模型生成步骤不进入这个事务。
    private MemoryConsolidationPersistenceService consolidationWriter(DatabaseStructuredMemoryService targetStore) {
        ProxyFactory proxy = new ProxyFactory(new MemoryConsolidationPersistenceService(targetStore, progress));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return (MemoryConsolidationPersistenceService) proxy.getProxy();
    }

    // 从临时表读取完整快照，不使用测试实体中尚未保存的时间戳。
    private MemoryConsolidationSnapshot consolidationSnapshot() {
        var entries = List.of(1L, 2L, 3L).stream().map(id -> {
            var memory = store.recallUserMemory(USER_ID, id);
            return new MemoryConsolidationEntry("memory_" + id, id, memory.getMemoryKey(), memory.getMemoryTopic(),
                    memory.getMemorySummary(), memory.getMemoryContent(), memory.getUpdatedAt());
        }).toList();
        return new MemoryConsolidationSnapshot(progress.find(USER, USER_ID), entries);
    }

    // 只合并同义的运动偏好，保留跑步次数这条独立事实。
    private MemoryConsolidationPlan consolidationPlan() {
        var plan = MemoryConsolidationTest.mergePlan();
        plan.getMerges().getFirst().setMemoryContent("最喜欢羽毛球");
        plan.getMerges().getFirst().setMemorySummary("最喜欢羽毛球");
        return plan;
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
