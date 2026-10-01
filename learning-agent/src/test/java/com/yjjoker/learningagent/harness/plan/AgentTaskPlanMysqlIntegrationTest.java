package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.CreateTaskStepRequest;
import com.yjjoker.learningagent.harness.plan.dto.UpdateTaskPlanRequest;
import com.yjjoker.learningagent.harness.plan.dto.UpdateTaskStepRequest;
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
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.ArrayList;
import java.util.Collections;
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
    void optionalLookupReturnsMissingThenSavedPlan() {
        String runId = UUID.randomUUID().toString();
        assertNull(service.loadIfPresent(runId, SESSION_ID));
        AgentTaskPlan saved = service.create(runId, SESSION_ID, request("专注查询测试"));
        AgentTaskPlan loaded = service.loadIfPresent(runId, SESSION_ID);
        assertNotNull(loaded);
        assertEquals(saved.getVersion(), loaded.getVersion());
        assertEquals(saved.getSteps().getFirst().getStepId(), loaded.getSteps().getFirst().getStepId());
    }

    // 可选读取仍要验证会话归属，不能用“不存在”掩盖越权请求。
    @Test
    void optionalLookupStillChecksOwnership() {
        assertThrows(LearningSessionStatusException.class,
                () -> service.loadIfPresent(UUID.randomUUID().toString(), 203L));
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

    // 通过真实更新服务交换顺序并取消一步，不直接修改 SQL 绕过服务校验。
    @Test
    void shouldKeepStepIdentityWhenOrderChangesAndRetainCanceledStep() throws Exception {
        String runId = UUID.randomUUID().toString();
        AgentTaskPlan created = service.create(runId, SESSION_ID, request("验证步骤身份"));
        UpdateTaskPlanRequest update = updateRequest(created);
        Collections.swap(update.getSteps(), 0, 1);
        update.getSteps().getLast().setStatus(AgentTaskStepStatus.CANCELED);
        update.getSteps().getLast().setResultSummary("不再需要此步");
        service.update(runId, SESSION_ID, update);
        AgentTaskPlan loaded = service.load(runId, SESSION_ID);
        assertEquals(2, loaded.getVersion());
        assertEquals(created.getSteps().getLast().getStepId(), loaded.getSteps().getFirst().getStepId());
        assertEquals(created.getSteps().getFirst().getStepId(), loaded.getSteps().getLast().getStepId());
        assertEquals(AgentTaskStepStatus.CANCELED, loaded.getSteps().getLast().getStatus());
        assertEquals("不再需要此步", loaded.getSteps().getLast().getResultSummary());
        assertEquals(2, countRows("agent_task_steps"));
    }

    // 版本改变后，旧请求不能覆盖新内容；没有实际变化的请求也不增加版本。
    @Test
    void shouldRejectStaleAndUnchangedRequestsWithoutPersistingVersionIncrement() {
        String runId = UUID.randomUUID().toString();
        AgentTaskPlan created = service.create(runId, SESSION_ID, request("保持原目标"));
        UpdateTaskPlanRequest start = updateRequest(created);
        start.getSteps().getFirst().setStatus(AgentTaskStepStatus.IN_PROGRESS);
        service.update(runId, SESSION_ID, start);
        AgentTaskPlan running = service.load(runId, SESSION_ID);
        UpdateTaskPlanRequest stale = updateRequest(created);
        stale.getSteps().getLast().setDescription("这份旧修改不应生效");
        assertThrows(ClientDataErrorException.class, () -> service.update(runId, SESSION_ID, stale));
        assertThrows(ClientDataErrorException.class, () -> service.update(runId, SESSION_ID, updateRequest(running)));
        AgentTaskPlan loaded = service.load(runId, SESSION_ID);
        assertEquals(2, loaded.getVersion());
        assertEquals(running.getUpdatedAt(), loaded.getUpdatedAt());
        assertEquals(running.getSteps(), loaded.getSteps());
    }

    // 非法状态组合和遗漏旧步骤都要撤销已经取得的版本，不能残留空版本变化。
    @Test
    void shouldRollbackVersionWhenStepValidationFails() {
        String runId = UUID.randomUUID().toString();
        service.create(runId, SESSION_ID, request("验证校验失败回滚"));
        AgentTaskPlan before = service.load(runId, SESSION_ID);
        UpdateTaskPlanRequest twoRunning = updateRequest(before);
        twoRunning.getSteps().forEach(step -> step.setStatus(AgentTaskStepStatus.IN_PROGRESS));
        assertThrows(ClientDataErrorException.class, () -> service.update(runId, SESSION_ID, twoRunning));
        UpdateTaskPlanRequest missing = updateRequest(before);
        missing.getSteps().removeLast();
        assertThrows(ClientDataErrorException.class, () -> service.update(runId, SESSION_ID, missing));
        AgentTaskPlan after = service.load(runId, SESSION_ID);
        assertEquals(before, after);
    }

    // 新增步骤和第一条更新已写入后，让后面的真实 SQL 失败，整批变化必须撤销。
    @Test
    void shouldRollbackVersionOrderInsertedStepAndEarlierUpdateOnSqlFailure() {
        String runId = UUID.randomUUID().toString();
        service.create(runId, SESSION_ID, request("验证更新整批回滚"));
        AgentTaskPlan before = service.load(runId, SESSION_ID);
        UpdateTaskPlanRequest update = updateRequest(before);
        update.getSteps().getFirst().setDescription("这次修改也应回滚");
        UpdateTaskStepRequest added = new UpdateTaskStepRequest();
        added.setDescription("新步骤也应回滚");
        added.setCompletionCriteria("有结果");
        added.setStatus(AgentTaskStepStatus.PENDING);
        update.getSteps().addFirst(added);
        AgentTaskPlanRepository failing = mock(AgentTaskPlanRepository.class, delegatesTo(repository));
        doAnswer(invocation -> {
            AgentTaskStep step = invocation.getArgument(2);
            if (step.getPosition() == 3) {
                // 制造数据库 NOT NULL 错误，不用模拟异常代替真实 SQL 失败。
                step.setDescription(null);
            }
            return repository.updateStep(invocation.getArgument(0), invocation.getArgument(1), step);
        }).when(failing).updateStep(any(), any(), any());
        assertThrows(DataIntegrityViolationException.class,
                () -> transactionalService(failing).update(runId, SESSION_ID, update));
        assertEquals(before, service.load(runId, SESSION_ID));
        assertEquals(2, countRows("agent_task_steps"));
        log.info("任务计划更新真实回滚通过：版本、顺序、新增步骤和前序修改全部还原");
    }

    // 已完成记录保持原样；需要重新执行时新增待执行步骤，而不是重置旧状态。
    @Test
    void shouldPreserveCompletedResultWhenAddingRedoStep() {
        String runId = UUID.randomUUID().toString();
        AgentTaskPlan plan = service.create(runId, SESSION_ID, request("保留执行历史"));
        UpdateTaskPlanRequest start = updateRequest(plan);
        start.getSteps().getFirst().setStatus(AgentTaskStepStatus.IN_PROGRESS);
        plan = service.update(runId, SESSION_ID, start);
        UpdateTaskPlanRequest finish = updateRequest(plan);
        finish.getSteps().getFirst().setStatus(AgentTaskStepStatus.COMPLETED);
        finish.getSteps().getFirst().setResultSummary("找到资料 A 和 B");
        service.update(runId, SESSION_ID, finish);
        AgentTaskPlan completed = service.load(runId, SESSION_ID);
        UpdateTaskPlanRequest rewrite = updateRequest(completed);
        rewrite.getSteps().getFirst().setResultSummary("偷偷改写过去结果");
        assertThrows(ClientDataErrorException.class, () -> service.update(runId, SESSION_ID, rewrite));
        UpdateTaskPlanRequest redo = updateRequest(completed);
        UpdateTaskStepRequest newStep = new UpdateTaskStepRequest();
        newStep.setDescription("检索更新后的资料");
        newStep.setCompletionCriteria("找到新的来源");
        newStep.setStatus(AgentTaskStepStatus.PENDING);
        redo.getSteps().add(newStep);
        service.update(runId, SESSION_ID, redo);
        AgentTaskPlan loaded = service.load(runId, SESSION_ID);
        assertEquals(4, loaded.getVersion());
        assertEquals(completed.getSteps().getFirst(), loaded.getSteps().getFirst());
        assertEquals(AgentTaskStepStatus.PENDING, loaded.getSteps().getLast().getStatus());
        assertNotEquals(loaded.getSteps().getFirst().getStepId(), loaded.getSteps().getLast().getStepId());
        assertEquals(completed.getGoal(), loaded.getGoal());
        assertEquals(completed.getConstraints(), loaded.getConstraints());
    }

    // 直接调用更新 SQL 也要匹配归属；另一任务的步骤编号不能混入当前计划。
    @Test
    void shouldScopeUpdateSqlAndRejectForeignStepIds() {
        String runId = UUID.randomUUID().toString();
        service.create(runId, SESSION_ID, request("当前任务"));
        AgentTaskPlan before = service.load(runId, SESSION_ID);
        AgentTaskPlan other = service.create(UUID.randomUUID().toString(), 202L, request("另一会话任务"));
        AgentTaskStep target = before.getSteps().getFirst();
        assertEquals(0, repository.advanceVersion(102L, SESSION_ID, runId, 1L, before.getUpdatedAt()));
        assertEquals(0, repository.advanceVersion(USER_ID, 202L, runId, 1L, before.getUpdatedAt()));
        assertEquals(0, repository.parkStepPositions(102L, SESSION_ID, runId, 20));
        assertEquals(0, repository.updateStep(USER_ID, 202L, target));
        UpdateTaskPlanRequest foreign = updateRequest(before);
        foreign.getSteps().getFirst().setStepId(other.getSteps().getFirst().getStepId());
        assertThrows(ClientDataErrorException.class, () -> service.update(runId, SESSION_ID, foreign));
        assertEquals(before, service.load(runId, SESSION_ID));
    }

    // 把刚读取的计划转成独立更新快照，不把数据库实体直接交给更新服务。
    private UpdateTaskPlanRequest updateRequest(AgentTaskPlan plan) {
        UpdateTaskPlanRequest update = new UpdateTaskPlanRequest();
        update.setExpectedVersion(plan.getVersion());
        List<UpdateTaskStepRequest> steps = new ArrayList<>();
        for (AgentTaskStep step : plan.getSteps()) {
            UpdateTaskStepRequest input = new UpdateTaskStepRequest();
            input.setStepId(step.getStepId());
            input.setDescription(step.getDescription());
            input.setCompletionCriteria(step.getCompletionCriteria());
            input.setStatus(step.getStatus());
            input.setResultSummary(step.getResultSummary());
            steps.add(input);
        }
        update.setSteps(steps);
        return update;
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
