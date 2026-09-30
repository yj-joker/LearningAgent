package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskStepRequest;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import com.yjjoker.learningagent.harness.plan.service.AgentTaskPlanService;
import com.yjjoker.learningagent.repository.AgentTaskPlanRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

// 显式开启才连接 MySQL；所有测试数据只写当前连接的临时表，不调用模型。
@EnabledIfEnvironmentVariable(named = "PLAN_MYSQL_TEST", matches = "true")
@Slf4j
class AgentTaskPlanMysqlIntegrationTest {
    private static final Long USER_ID = 101L;
    private static final Long SESSION_ID = 201L;
    private Connection connection;
    private SingleConnectionDataSource dataSource;
    private AgentTaskPlanRepository repository;
    private LearningSessionRepository sessions;
    private AgentTaskPlanService service;

    // 从完整建表文件建立临时表，并接上真实 MyBatis 映射和 Spring 事务。
    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:mysql://" + env("MYSQL_HOST", "localhost") + ":" + env("MYSQL_PORT", "3306")
                + "/" + env("MYSQL_DATABASE", "learning_agent")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=3000&socketTimeout=10000";
        connection = DriverManager.getConnection(url, env("MYSQL_USER", "root"), System.getenv("MYSQL_PASSWORD"));
        dataSource = new SingleConnectionDataSource(connection, true);
        try (Statement statement = connection.createStatement()) {
            // 临时表遮蔽同名业务表；关闭此连接后自动消失，不执行 DROP 业务表。
            for (String table : List.of("learning_sessions", "agent_task_plans", "agent_task_steps")) {
                statement.execute(tableDdl(table).replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMPORARY TABLE"));
            }
            statement.executeUpdate("INSERT INTO learning_sessions (id, course_id, user_id, session_title, status) VALUES "
                    + "(201, 1, 101, '计划测试', 'ACTIVE'), (202, 1, 101, '同用户另一会话', 'ACTIVE'),"
                    + "(203, 1, 102, '其他用户会话', 'ACTIVE')");
        }
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(AgentTaskPlanRepository.class);
        configuration.addMapper(LearningSessionRepository.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        repository = template.getMapper(AgentTaskPlanRepository.class);
        sessions = template.getMapper(LearningSessionRepository.class);
        service = transactionalService(repository);
        BaseContext.setCurrentId(USER_ID);
    }

    // 同时清理线程身份和数据库连接；失败用例也不能留下测试上下文。
    @AfterEach
    void tearDown() throws Exception {
        BaseContext.removeCurrentId();
        if (connection != null) {
            connection.close();
        }
    }

    // 不建立任何审批记录，也能创建并读回一份完整计划。
    @Test
    void shouldRoundTripCompletePlanWithoutApproval() {
        String runId = UUID.randomUUID().toString();
        AgentTaskPlan created = service.create(runId, SESSION_ID, request("生成 Java 练习题"));
        AgentTaskPlan loaded = service.load(runId, SESSION_ID);
        assertEquals(1L, loaded.getVersion());
        assertEquals("生成 Java 练习题", loaded.getGoal());
        assertEquals("五道题，附答案", loaded.getConstraints());
        assertEquals(USER_ID, loaded.getUserId());
        assertEquals(SESSION_ID, loaded.getSessionId());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
        assertEquals(List.of(1, 2), loaded.getSteps().stream().map(AgentTaskStep::getPosition).toList());
        assertEquals(created.getSteps().stream().map(AgentTaskStep::getStepId).toList(),
                loaded.getSteps().stream().map(AgentTaskStep::getStepId).toList());
        assertEquals("找到资料来源", loaded.getSteps().getFirst().getCompletionCriteria());
        assertEquals(AgentTaskStepStatus.PENDING, loaded.getSteps().getFirst().getStatus());
        assertNull(loaded.getSteps().getFirst().getResultSummary());
        log.info("任务计划真实 MySQL 读写通过，planCount={}，stepCount={}", countRows("agent_task_plans"), countRows("agent_task_steps"));
    }

    // 相同会话内的两个任务各有自己的步骤，不能按会话直接混合。
    @Test
    void shouldKeepDifferentRunsSeparate() {
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        service.create(first, SESSION_ID, request("学习 Java"));
        service.create(second, SESSION_ID, request("学习网络"));
        assertEquals("学习 Java", service.load(first, SESSION_ID).getGoal());
        assertEquals("学习网络", service.load(second, SESSION_ID).getGoal());
        assertEquals(2, countRows("agent_task_plans"));
        assertEquals(4, countRows("agent_task_steps"));
    }

    // 服务和真实 SQL 都按用户、会话隔离，不能只靠上层的一次检查。
    @Test
    void shouldEnforceUserAndSessionScopeInSql() {
        String runId = UUID.randomUUID().toString();
        service.create(runId, SESSION_ID, request("学习 Java"));
        assertTrue(repository.findPlan(102L, SESSION_ID, runId).isEmpty());
        assertTrue(repository.findSteps(102L, SESSION_ID, runId).isEmpty());
        assertTrue(repository.findPlan(USER_ID, 202L, runId).isEmpty());
        assertTrue(repository.findSteps(USER_ID, 202L, runId).isEmpty());
        assertThrows(ClientDataErrorException.class, () -> service.load(runId, 202L));
        BaseContext.setCurrentId(102L);
        assertThrows(LearningSessionStatusException.class, () -> service.load(runId, SESSION_ID));
        assertThrows(ClientDataErrorException.class, () -> service.load(runId, 203L));
    }

    // 重复任务由数据库主键拒绝；原目标、原步骤编号和版本都必须保留。
    @Test
    void shouldRejectDuplicateWithoutOverwritingExistingPlan() {
        String runId = UUID.randomUUID().toString();
        AgentTaskPlan original = service.create(runId, SESSION_ID, request("原始目标"));
        assertThrows(ClientDataErrorException.class, () -> service.create(runId, SESSION_ID, request("不应覆盖")));
        AgentTaskPlan loaded = service.load(runId, SESSION_ID);
        assertEquals("原始目标", loaded.getGoal());
        assertEquals(1L, loaded.getVersion());
        assertEquals(original.getSteps().stream().map(AgentTaskStep::getStepId).toList(),
                loaded.getSteps().stream().map(AgentTaskStep::getStepId).toList());
        assertEquals(1, countRows("agent_task_plans"));
        assertEquals(2, countRows("agent_task_steps"));
    }

    // 第二步故意写入重复顺序，让 MySQL 真正报错，检查计划和第一步都被回滚。
    @Test
    void shouldRollbackPlanAndFirstStepWhenSecondSqlFails() {
        AgentTaskPlanRepository failing = mock(AgentTaskPlanRepository.class, delegatesTo(repository));
        doAnswer(invocation -> {
            AgentTaskStep step = invocation.getArgument(0);
            if (step.getPosition() == 2) {
                step.setPosition(1);
            }
            return repository.insertStep(step);
        }).when(failing).insertStep(any());
        AgentTaskPlanService failingService = transactionalService(failing);
        assertThrows(DuplicateKeyException.class,
                () -> failingService.create(UUID.randomUUID().toString(), SESSION_ID, request("必须整体回滚")));
        assertEquals(0, countRows("agent_task_plans"));
        assertEquals(0, countRows("agent_task_steps"));
        log.info("任务计划真实事务回滚通过：第二步 SQL 失败后，两表均无残留");
    }

    // 只检验表结构能支持未来的重排和取消；第一阶段没有开放这些更新入口。
    @Test
    void shouldKeepStepIdentityWhenOrderChangesAndRetainCanceledStep() throws Exception {
        String runId = UUID.randomUUID().toString();
        AgentTaskPlan created = service.create(runId, SESSION_ID, request("验证步骤身份"));
        try (Statement statement = connection.createStatement()) {
            // 先移出原位置再交换，避免交换过程中撞到顺序唯一约束。
            statement.executeUpdate("UPDATE agent_task_steps SET position = position + 10");
            statement.executeUpdate("UPDATE agent_task_steps SET position = 13 - position");
            statement.executeUpdate("UPDATE agent_task_steps SET status = 'CANCELED', result_summary = '不再需要此步' WHERE position = 2");
        }
        AgentTaskPlan loaded = service.load(runId, SESSION_ID);
        assertEquals(created.getSteps().getLast().getStepId(), loaded.getSteps().getFirst().getStepId());
        assertEquals(created.getSteps().getFirst().getStepId(), loaded.getSteps().getLast().getStepId());
        assertEquals(AgentTaskStepStatus.CANCELED, loaded.getSteps().getLast().getStatus());
        assertEquals("不再需要此步", loaded.getSteps().getLast().getResultSummary());
        assertEquals(2, countRows("agent_task_steps"));
    }

    // 数据库也拒绝非法版本、步骤状态与顺序，防止跳过 Java 校验后写入脏数据。
    @Test
    void shouldEnforceSchemaConstraints() throws Exception {
        service.create(UUID.randomUUID().toString(), SESSION_ID, request("验证约束"));
        try (Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE agent_task_plans SET version = 0"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE agent_task_steps SET status = 'UNKNOWN'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE agent_task_steps SET position = 0 WHERE position = 1"));
        }
    }

    // Java 限长与 utf8mb4 列宽一致，较长中文和表情也能真实入库并读回。
    @Test
    void shouldPersistUnicodeAtDeclaredLengthLimits() {
        CreateTaskPlanRequest request = request("字".repeat(AgentTaskPlanService.MAX_GOAL_LENGTH));
        request.setConstraints("字".repeat(AgentTaskPlanService.MAX_CONSTRAINTS_LENGTH));
        request.getSteps().getFirst().setDescription("📚".repeat(AgentTaskPlanService.MAX_STEP_TEXT_LENGTH));
        String runId = UUID.randomUUID().toString();
        service.create(runId, SESSION_ID, request);
        AgentTaskPlan loaded = service.load(runId, SESSION_ID);
        assertEquals(request.getGoal(), loaded.getGoal());
        assertEquals(request.getSteps().getFirst().getDescription(), loaded.getSteps().getFirst().getDescription());
    }

    // 用 Spring 真实事务代理调用服务，而不是直接 new 后假定注解会自动生效。
    private AgentTaskPlanService transactionalService(AgentTaskPlanRepository planRepository) {
        ProxyFactory proxy = new ProxyFactory(new AgentTaskPlanService(planRepository, sessions));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (AgentTaskPlanService) proxy.getProxy();
    }

    // 只允许抽取这三个测试表，复用完整建表定义，不维护第二份 SQL。
    private String tableDdl(String table) throws Exception {
        if (!List.of("learning_sessions", "agent_task_plans", "agent_task_steps").contains(table)) {
            throw new IllegalArgumentException("不支持的计划测试表");
        }
        String schema = new ClassPathResource("db/migration/learningAgentSql.sql").getContentAsString(StandardCharsets.UTF_8);
        var match = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + Pattern.quote(table) + "\\s*\\(.*?;").matcher(schema);
        if (!match.find()) {
            throw new IllegalStateException("完整建表文件缺少计划测试表");
        }
        return match.group();
    }

    // 只统计当前连接的临时计划表，确认成功记录或回滚后的残留。
    private int countRows(String table) {
        if (!List.of("agent_task_plans", "agent_task_steps").contains(table)) {
            throw new IllegalArgumentException("不支持的统计表");
        }
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法检查临时表中的计划数量", exception);
        }
    }

    // 测试只从进程环境读取连接参数，密码不写入源码或日志。
    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    // 构造一份固定的两步计划，测试数据不含真实用户内容。
    private CreateTaskPlanRequest request(String goal) {
        CreateTaskPlanRequest request = new CreateTaskPlanRequest();
        request.setGoal(goal);
        request.setConstraints("五道题，附答案");
        CreateTaskStepRequest first = new CreateTaskStepRequest();
        first.setDescription("检索资料");
        first.setCompletionCriteria("找到资料来源");
        CreateTaskStepRequest second = new CreateTaskStepRequest();
        second.setDescription("生成题目");
        second.setCompletionCriteria("五道题均有答案");
        request.setSteps(List.of(first, second));
        return request;
    }
}
