package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.config.MemoryConsolidationProperties;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.memory.impl.DatabaseStructuredMemoryService;
import com.yjjoker.learningagent.harness.memory.impl.LlmMemoryConsolidator;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.*;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.*;
import com.yjjoker.learningagent.utils.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static com.yjjoker.learningagent.harness.memory.MemoryTestData.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryApprovalStatus.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryScope.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 两个开关都打开才访问真实服务；所有数据库写入只进入连接级临时表。
// 真实模型、MyBatis SQL 和事务全部保留，不把生成结果或审批保存替换成假实现。
@EnabledIfEnvironmentVariable(named = "MEMORY_MYSQL_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "MEMORY_ALIYUN_TEST", matches = "true")
@Slf4j
class MemoryConsolidationApprovalAliyunMysqlTest {
    private Connection connection;
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryApprovalRepository approvals;
    private MemoryConsolidationRepository progress;
    private DatabaseStructuredMemoryService store;
    private MemoryConsolidationService coordinator;
    private MemoryApprovalService decisions;
    private LlmClient client;

    // 建立隔离表并连接真实模型；不启动开发服务器，也不读取用户的业务正文。
    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:mysql://" + env("MYSQL_HOST", "localhost") + ":" + env("MYSQL_PORT", "3306")
                + "/" + env("MYSQL_DATABASE", "learning_agent")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        connection = DriverManager.getConnection(url, env("MYSQL_USER", "root"), System.getenv("MYSQL_PASSWORD"));
        dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        createTemporaryTables();

        // 使用生产 Repository 的映射，不在测试中另写一套审批保存 SQL。
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(UserMemoryRepository.class, SessionMemoryRepository.class,
                MemoryConsolidationRepository.class, MemoryApprovalRepository.class, LearningSessionRepository.class)) {
            configuration.addMapper(mapper);
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var template = new SqlSessionTemplate(factory.getObject());
        approvals = template.getMapper(MemoryApprovalRepository.class);
        progress = template.getMapper(MemoryConsolidationRepository.class);
        var sessions = template.getMapper(LearningSessionRepository.class);
        store = transactional(new DatabaseStructuredMemoryService(template.getMapper(UserMemoryRepository.class),
                template.getMapper(SessionMemoryRepository.class)));
        var writer = transactional(new MemoryConsolidationPersistenceService(store, progress));
        // 通知不影响真实模型与数据库验证；网络通知在独立测试中验证。
        var notifier = mock(com.yjjoker.learningagent.notification.ApprovalNotifier.class);
        var proposals = transactional(new MemoryConsolidationApprovalService(approvals, sessions, writer, notifier));
        var properties = new MemoryConsolidationProperties();
        // spy 只统计调用次数，所有请求仍由真实 Aliyun 客户端发送。
        client = spy(MemoryTargetAliyunTest.client());
        var model = new LlmMemoryConsolidator(client, new LlmRetryExecutor(), properties);
        coordinator = new MemoryConsolidationService(properties, progress, store, model, writer, proposals);
        var scheduler = new MemoryConsolidationScheduler(coordinator, task -> {
            // 本测试只批准整理申请，不走普通变更的后台通知；误触发时直接让测试失败。
            fail("整理审批不应再次调度普通记忆变更任务");
        });
        decisions = transactional(new MemoryApprovalService(approvals,
                transactional(new MemoryCandidatePersistenceService(store, progress)), sessions, proposals, scheduler, notifier));

        // 固定身份仅用于本连接的合成数据；关闭连接后连同临时表一起清理。
        LearningSession session = new LearningSession();
        session.setCourseId(1L);
        session.setUserId(USER_ID);
        session.setSessionTitle("真实模型整理审批隔离验收");
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(session.getCreatedAt());
        sessions.createSession(session);
        jdbc.update("UPDATE learning_sessions SET id=? WHERE id=?", SESSION_ID, session.getId());
        store.saveUserMemory(user(1, "favoriteSport", "用户最喜欢羽毛球，每周六去体育馆打球。"));
        store.saveUserMemory(user(2, "userFavoriteSport", "用户最喜欢的运动是羽毛球，通常和同事一起打球。"));
        store.saveUserMemory(user(3, "runningFrequency", "每周跑步三次。"));
        // 审批主路径用明确重复的事实；不同先后步骤另测，不强迫模型把独立事项合并。
        store.saveSessionMemory(session(1, "learningGoal", "本会话的学习目标是掌握 Java 集合，重点练习 ArrayList 和 HashMap。"));
        store.saveSessionMemory(session(2, "currentGoal", "本会话的学习目标是掌握 Java 集合，重点练习 ArrayList 和 HashMap。"));
        store.saveSessionMemory(session(3, "meetingTime", "本次学习讨论定在周日晚上八点。"));
        progress.initialize(USER, USER_ID, 20);
        progress.initialize(SESSION, SESSION_ID, 20);
        BaseContext.setCurrentId(USER_ID);
    }

    // 清理身份和连接；不对真实表执行 DELETE 或 DROP。
    @AfterEach
    void tearDown() throws Exception {
        BaseContext.removeCurrentId();
        if (connection != null) {
            connection.close();
        }
    }

    // 两类范围都必须先等待批准；批准后的正文保留两条原记忆的互补细节。
    @ParameterizedTest
    @EnumSource(MemoryScope.class)
    void shouldApproveRealPlanOnlyAfterUserDecision(MemoryScope scope) {
        List<String> before = memoryRows(scope);
        MemoryScope other = scope == USER ? SESSION : USER;
        List<String> untouched = memoryRows(other);
        MemoryApprovalRequest request = generateProposal(scope);
        long calls = modelCalls();
        assertTrue(calls > 0);
        assertEquals(before, memoryRows(scope), "生成提案不得修改记忆");
        assertEquals(0, progress.find(scope, owner(scope)).getProcessedCount());
        assertEquals(PENDING, request.getStatus());

        // 待审批时重复触发，不能再调用模型或创建另一份申请。
        coordinator.consolidateScope(USER_ID, SESSION_ID, scope);
        assertEquals(calls, modelCalls());
        assertEquals(1, approvalCount());
        var result = decisions.approve(request.getId());
        assertEquals(APPROVED, result.getStatus());
        assertEquals(APPROVED, approvals.findById(request.getId()).getStatus());
        assertEquals(2, activeCount(scope));
        assertEquals(3, memoryRows(scope).size(), "来源只软删除，不能物理删除");
        assertEquals(20, progress.find(scope, owner(scope)).getProcessedCount());
        assertEquals(untouched, memoryRows(other), "不能修改另一范围的记忆");

        // 读取模型实际选择的保留目标，不假定模型一定保留第一条。
        var plan = new JsonMapper().readValue(request.getCandidateJson(), MemoryConsolidationPlan.class);
        var root = new JsonMapper().readTree(request.getTargetSnapshotJson());
        String keepRef = plan.getMerges().getFirst().getKeepRef();
        long keepId = 0;
        for (var entry : root.get("entries")) {
            if (entry.get("memoryRef").asString().equals(keepRef)) {
                keepId = entry.get("memoryId").asLong();
            }
        }
        String content = scope == USER ? store.recallUserMemory(USER_ID, keepId).getMemoryContent()
                : store.recallSessionMemory(SESSION_ID, keepId).getMemoryContent();
        for (String detail : scope == USER ? List.of("羽毛球", "周六", "体育馆", "同事")
                : List.of("Java", "ArrayList", "HashMap")) {
            assertTrue(content.contains(detail), "合并后丢失细节：" + detail);
        }
        assertThrows(ClientDataErrorException.class, () -> decisions.approve(request.getId()));
        assertEquals(calls, modelCalls(), "批准及重复批准不能再次请求模型");
        log.info("真实整理审批验收通过：scope={}，模型请求={}，审批前有效数=3，审批后有效数=2，进度=20，互补细节保留", scope, calls);
    }

    // 拒绝真实模型方案后不写记忆，同一数据版本不会再次申请。
    @Test
    void shouldRejectRealPlanWithoutChangingMemoriesOrProgress() {
        List<String> before = memoryRows(USER);
        MemoryApprovalRequest request = generateProposal(USER);
        long calls = modelCalls();
        assertEquals(REJECTED, decisions.reject(request.getId(), "隔离验收：拒绝本次合并").getStatus());
        coordinator.consolidateScope(USER_ID, SESSION_ID, USER);
        assertEquals(REJECTED, approvals.findById(request.getId()).getStatus());
        assertEquals(before, memoryRows(USER));
        assertEquals(0, progress.find(USER, USER_ID).getProcessedCount());
        assertEquals(1, approvalCount());
        assertEquals(calls, modelCalls());
        log.info("真实整理审批验收通过：拒绝不修改记忆，同版本不重新请求模型，进度仍为0");
    }

    // 模拟等待审批时出现新事实，旧方案必须变成 STALE，不能覆盖用户的新偏好。
    @Test
    void shouldMarkRealPlanStaleAfterNewFactCommits() {
        MemoryApprovalRequest request = generateProposal(USER);
        long calls = modelCalls();
        var changed = store.recallUserMemory(USER_ID, 2L);
        changed.setMemoryContent("用户现在最喜欢足球。");
        store.updateUserMemory(changed);
        progress.addChanges(USER, USER_ID, 1);
        List<String> latest = memoryRows(USER);
        assertEquals(STALE, decisions.approve(request.getId()).getStatus());
        assertEquals(STALE, approvals.findById(request.getId()).getStatus());
        assertEquals(latest, memoryRows(USER));
        assertEquals(21, progress.find(USER, USER_ID).getChangeCount());
        assertEquals(0, progress.find(USER, USER_ID).getProcessedCount());
        assertEquals(calls, modelCalls());
        log.info("真实整理审批验收通过：旧方案标记STALE，保留足球新事实，累计变更=21，已整理=0");
    }

    // 不依赖模型再次返回相同结果，直接向真实唯一索引验证两种重复保护。
    @Test
    void shouldRejectDuplicateRealProposalInDatabase() {
        MemoryApprovalRequest request = generateProposal(USER);
        MemoryApprovalRequest duplicate = new JsonMapper().readValue(
                new JsonMapper().writeValueAsString(request), MemoryApprovalRequest.class);
        duplicate.setId(null);
        // 同一范围仍有待审批时，即使版本不同也不能插入第二份。
        duplicate.setSnapshotChangeCount(request.getSnapshotChangeCount() + 1);
        assertThrows(DuplicateKeyException.class, () -> approvals.insert(duplicate));
        decisions.reject(request.getId(), "隔离验收：检查版本唯一索引");
        duplicate.setSnapshotChangeCount(request.getSnapshotChangeCount());
        assertThrows(DuplicateKeyException.class, () -> approvals.insert(duplicate));
        assertEquals(1, approvalCount());
        assertEquals(3, activeCount(USER));
        log.info("真实整理审批验收通过：MySQL拦截待审批范围重复及已处理版本重复");
    }

    // 不把有先后关系的两个学习步骤硬判为重复；两种合法方案都必须完整保留信息。
    @Test
    void shouldPreserveSequentialGoalsWithEitherSafePlan() {
        var first = store.recallSessionMemory(SESSION_ID, 1L);
        first.setMemorySummary("先练习 ArrayList");
        first.setMemoryContent("本会话的目标是学习 Java 集合，先练习 ArrayList。");
        store.updateSessionMemory(first);
        var second = store.recallSessionMemory(SESSION_ID, 2L);
        second.setMemorySummary("接下来练习 HashMap");
        second.setMemoryContent("当前会话正在学习 Java 集合，接下来练习 HashMap。");
        store.updateSessionMemory(second);
        List<String> before = memoryRows(SESSION);
        coordinator.consolidateScope(USER_ID, SESSION_ID, SESSION);
        List<MemoryApprovalRequest> pending = approvals.findPendingByUserId(USER_ID);
        assertEquals(before, memoryRows(SESSION), "模型无论怎么分组，批准前都不能修改记忆");
        if (pending.isEmpty()) {
            // 空方案也是成功完成检查，不应为了得到审批而重试到模型同意合并。
            assertEquals(3, activeCount(SESSION));
            assertEquals(20, progress.find(SESSION, SESSION_ID).getProcessedCount());
            log.info("真实顺序任务验收：模型选择不合并，三条记忆原样保留");
        } else {
            assertEquals(1, pending.size());
            assertEquals(APPROVED, decisions.approve(pending.getFirst().getId()).getStatus());
            assertEquals(2, activeCount(SESSION));
            log.info("真实顺序任务验收：模型选择合并，批准后继续核对全部细节");
        }
        String activeContent = String.join("\n", jdbc.queryForList(
                "SELECT memory_content FROM session_memories WHERE status='ACTIVE' ORDER BY id", String.class));
        for (String detail : List.of("ArrayList", "HashMap", "周日", "八点")) {
            assertTrue(activeContent.contains(detail), "不能丢失任务细节：" + detail);
        }
        assertEquals("本次学习讨论定在周日晚上八点。", store.recallSessionMemory(SESSION_ID, 3L).getMemoryContent());
    }

    // 在最后一步制造真实数据库约束错误，前面已执行的合并、软删除和进度必须一起回滚。
    @Test
    void shouldRollbackMemoryAndProgressWhenApprovalUpdateFails() {
        MemoryApprovalRequest request = generateProposal(USER);
        List<String> before = memoryRows(USER);
        // 约束只加在连接级临时表上；禁止 APPROVED 用来模拟审批状态落库失败。
        jdbc.execute("ALTER TABLE memory_approval_requests ADD CONSTRAINT test_reject_approved CHECK(status <> 'APPROVED')");
        // MySQL 3819 在当前驱动中被包装成未分类 SQL 异常，核对根因而不是猜包装子类。
        DataAccessException error = assertThrows(DataAccessException.class, () -> decisions.approve(request.getId()));
        SQLException cause = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(3819, cause.getErrorCode());
        assertTrue(cause.getMessage().contains("test_reject_approved"));
        assertEquals(before, memoryRows(USER));
        assertEquals(0, progress.find(USER, USER_ID).getProcessedCount());
        assertEquals(PENDING, approvals.findById(request.getId()).getStatus());
        log.info("真实事务回滚验收通过：审批更新失败，记忆、软删除、进度全部回滚，申请仍待审批");
    }

    // 每次真实调用后读取数据库中的申请，而不是只检查模型回答文字。
    private MemoryApprovalRequest generateProposal(MemoryScope scope) {
        coordinator.consolidateScope(USER_ID, SESSION_ID, scope);
        List<MemoryApprovalRequest> pending = approvals.findPendingByUserId(USER_ID);
        assertEquals(1, pending.size(), "预期生成一份真实整理方案并保存待审批");
        MemoryApprovalRequest request = pending.getFirst();
        assertEquals(MemoryApprovalType.CONSOLIDATION, request.getApprovalType());
        assertEquals(scope, request.getScope());
        return request;
    }

    // 复用实际表定义；临时同名表只对当前连接可见，不覆盖其他连接的业务表。
    private void createTemporaryTables() throws Exception {
        try (Statement statement = connection.createStatement()) {
            // 五张表都从统一文件取得完整定义，不再先建旧表再执行增量迁移。
            for (String table : List.of("learning_sessions", "user_memories", "session_memories",
                    "memory_consolidation_state", "memory_approval_requests")) {
                statement.execute(tableDdl(table).replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMPORARY TABLE"));
            }
        }
    }

    // 加入实际 Spring 事务代理，嵌套服务共享连接并按 @Transactional 提交或回滚。
    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    // 返回包含正文和状态的稳定快照，检查审批前后是否发生意外修改。
    private List<String> memoryRows(MemoryScope scope) {
        return jdbc.queryForList("SELECT CONCAT(id, ':', memory_key, ':', status, ':', memory_content) FROM "
                + table(scope) + " ORDER BY id", String.class);
    }

    // 只统计仍可召回的记忆，不把软删除的来源算成有效记录。
    private int activeCount(MemoryScope scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table(scope) + " WHERE status='ACTIVE'", Integer.class);
    }

    // 临时审批表只含本测试数据，可以直接统计全部申请。
    private int approvalCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memory_approval_requests", Integer.class);
    }

    // 统计真正进入客户端的请求，不打印消息正文或鉴权信息。
    private long modelCalls() {
        return mockingDetails(client).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("generateWithoutTools")).count();
    }

    // 表名只由后端枚举选择，不拼接用户输入。
    private String table(MemoryScope scope) {
        return scope == USER ? "user_memories" : "session_memories";
    }

    // 两类范围使用各自的归属主键。
    private Long owner(MemoryScope scope) {
        return scope == USER ? USER_ID : SESSION_ID;
    }

    // 只读取环境变量，不把数据库密码或模型密钥写进测试代码。
    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
