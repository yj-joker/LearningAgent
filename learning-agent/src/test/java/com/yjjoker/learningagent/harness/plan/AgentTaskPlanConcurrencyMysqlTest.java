package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

// 多连接不能共享连接级临时表，所以使用独立随机测试库，结束后只删除本次测试库。
@EnabledIfEnvironmentVariable(named = "PLAN_MYSQL_TEST", matches = "true")
@Slf4j
class AgentTaskPlanConcurrencyMysqlTest {
    private static final Long USER_ID = 101L;
    private static final Long SESSION_ID = 201L;
    private String schema;
    private boolean schemaCreated;
    private DriverManagerDataSource dataSource;
    private AgentTaskPlanRepository repository;
    private LearningSessionRepository sessions;
    private AgentTaskPlanService service;

    // 独立数据库只建当前测试需要的三张空表，不拷贝真实用户资料。
    @BeforeEach
    void setUp() throws Exception {
        schema = "harness_plan_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quotedSchema() + " CHARACTER SET utf8mb4");
            schemaCreated = true;
        }
        dataSource = new DriverManagerDataSource(url(schema), env("MYSQL_USER", "root"), System.getenv("MYSQL_PASSWORD"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("learning_sessions", "agent_task_plans", "agent_task_steps")) {
                statement.execute(tableDdl(table));
            }
            statement.executeUpdate("INSERT INTO learning_sessions (id, course_id, user_id, session_title, status) "
                    + "VALUES (201, 1, 101, '计划并发测试', 'ACTIVE')");
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
        log.info("计划并发测试库已创建，schema={}", schema);
    }

    // 只清理本次成功创建的随机测试库；名称校验阻止误删业务库。
    @AfterEach
    void tearDown() throws Exception {
        BaseContext.removeCurrentId();
        if (schemaCreated) {
            try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + quotedSchema());
                log.info("计划并发测试库已清理，schema={}", schema);
            }
        }
    }

    // 两个请求同时基于版本 1 更新，只允许先取得修改权的请求成功。
    @Test
    void shouldAllowOnlyOneWriterForTheSameVersion() throws Exception {
        AgentTaskPlan original = createPlan();
        UpdateTaskPlanRequest firstRequest = updateRequest(original);
        firstRequest.getSteps().getFirst().setDescription("先取得修改权的结果");
        UpdateTaskPlanRequest secondRequest = updateRequest(original);
        secondRequest.getSteps().getFirst().setDescription("不应覆盖的旧版本结果");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondAttempt = new CountDownLatch(1);
        AgentTaskPlanRepository firstRepository = mock(AgentTaskPlanRepository.class, delegatesTo(repository));
        doAnswer(invocation -> {
            int changed = repository.advanceVersion(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4));
            locked.countDown();
            await(release);
            return changed;
        }).when(firstRepository).advanceVersion(any(), any(), any(), anyLong(), any());
        AgentTaskPlanRepository secondRepository = mock(AgentTaskPlanRepository.class, delegatesTo(repository));
        doAnswer(invocation -> {
            secondAttempt.countDown();
            return repository.advanceVersion(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4));
        }).when(secondRepository).advanceVersion(any(), any(), any(), anyLong(), any());
        AgentTaskPlanService firstWriter = transactionalService(firstRepository);
        AgentTaskPlanService secondWriter = transactionalService(secondRepository);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                Future<AgentTaskPlan> first = workers.submit(() -> asUser(() -> firstWriter.update(original.getRunId(), SESSION_ID, firstRequest)));
                await(locked);
                Future<AgentTaskPlan> second = workers.submit(() -> asUser(() -> secondWriter.update(original.getRunId(), SESSION_ID, secondRequest)));
                await(secondAttempt);
                assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(2, first.get(10, TimeUnit.SECONDS).getVersion());
                ExecutionException failure = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
                assertInstanceOf(ClientDataErrorException.class, failure.getCause());
            } finally {
                // 即使断言失败也释放闸门，避免清理测试库时仍有事务持锁。
                release.countDown();
            }
        }
        AgentTaskPlan finalPlan = service.load(original.getRunId(), SESSION_ID);
        assertEquals(2, finalPlan.getVersion());
        assertEquals("先取得修改权的结果", finalPlan.getSteps().getFirst().getDescription());
        log.info("计划双连接竞争通过：一次更新成功，另一次收到版本冲突");
    }

    // 事务中已有旧快照时，取得新版本后仍必须按最新步骤校验，不能拿旧状态判断。
    @Test
    void shouldReadCurrentStepsAfterClaimingVersionEvenWithEarlierSnapshot() throws Exception {
        AgentTaskPlan initial = createPlan();
        CountDownLatch snapshotReady = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<UpdateTaskPlanRequest> finishRequest = new AtomicReference<>();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var workers = Executors.newSingleThreadExecutor()) {
            try {
                Future<AgentTaskPlan> finisher = workers.submit(() -> asUser(() -> transaction.execute(status -> {
                    assertEquals(1, repository.findPlan(USER_ID, SESSION_ID, initial.getRunId()).orElseThrow().getVersion());
                    assertEquals(AgentTaskStepStatus.PENDING, repository.findSteps(USER_ID, SESSION_ID, initial.getRunId()).getFirst().getStatus());
                    snapshotReady.countDown();
                    await(resume);
                    return service.update(initial.getRunId(), SESSION_ID, finishRequest.get());
                })));
                await(snapshotReady);
                UpdateTaskPlanRequest start = updateRequest(initial);
                start.getSteps().getFirst().setStatus(AgentTaskStepStatus.IN_PROGRESS);
                AgentTaskPlan running = service.update(initial.getRunId(), SESSION_ID, start);
                UpdateTaskPlanRequest finish = updateRequest(running);
                finish.getSteps().getFirst().setStatus(AgentTaskStepStatus.COMPLETED);
                finish.getSteps().getFirst().setResultSummary("当前步骤确实已经开始并完成");
                finishRequest.set(finish);
                resume.countDown();
                assertEquals(3, finisher.get(10, TimeUnit.SECONDS).getVersion());
            } finally {
                resume.countDown();
            }
        }
        assertEquals(AgentTaskStepStatus.COMPLETED, service.load(initial.getRunId(), SESSION_ID).getSteps().getFirst().getStatus());
        log.info("计划当前读验证通过：旧事务快照不会干扰新版本的状态校验");
    }

    // 读取计划和步骤之间发生另一次提交时，两次查询仍应返回同一版本的数据。
    @Test
    void shouldNotMixOldPlanVersionWithNewStepsDuringLoad() throws Exception {
        AgentTaskPlan initial = createPlan();
        CountDownLatch planRead = new CountDownLatch(1);
        CountDownLatch resumeRead = new CountDownLatch(1);
        AgentTaskPlanRepository reading = mock(AgentTaskPlanRepository.class, delegatesTo(repository));
        doAnswer(invocation -> {
            Object plan = repository.findPlan(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            planRead.countDown();
            await(resumeRead);
            return plan;
        }).when(reading).findPlan(any(), any(), any());
        AgentTaskPlanService reader = transactionalService(reading);
        try (var workers = Executors.newSingleThreadExecutor()) {
            try {
                Future<AgentTaskPlan> loading = workers.submit(() -> asUser(() -> reader.load(initial.getRunId(), SESSION_ID)));
                await(planRead);
                UpdateTaskPlanRequest update = updateRequest(initial);
                update.getSteps().getFirst().setStatus(AgentTaskStepStatus.IN_PROGRESS);
                service.update(initial.getRunId(), SESSION_ID, update);
                resumeRead.countDown();
                AgentTaskPlan snapshot = loading.get(10, TimeUnit.SECONDS);
                assertEquals(1, snapshot.getVersion());
                assertEquals(AgentTaskStepStatus.PENDING, snapshot.getSteps().getFirst().getStatus());
                assertEquals(2, service.load(initial.getRunId(), SESSION_ID).getVersion());
            } finally {
                resumeRead.countDown();
            }
        }
    }

    // 每个工作线程显式绑定测试用户，结束时清理，不依赖 ThreadLocal 自动跨线程传播。
    private <T> T asUser(Callable<T> action) throws Exception {
        BaseContext.setCurrentId(USER_ID);
        try {
            return action.call();
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    // 使用有超时的闸门协调线程，不让失败用例无限等待。
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("计划并发测试等待超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("计划并发测试被中断", exception);
        }
    }

    // 使用实际事务代理和多连接数据源，让不同线程真正竞争同一数据库行。
    private AgentTaskPlanService transactionalService(AgentTaskPlanRepository planRepository) {
        ProxyFactory proxy = new ProxyFactory(new AgentTaskPlanService(planRepository, sessions));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return (AgentTaskPlanService) proxy.getProxy();
    }

    // 仅连接默认库来创建和删除随机测试库，密码只从环境读取。
    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(url(""), env("MYSQL_USER", "root"), System.getenv("MYSQL_PASSWORD"));
    }

    // 给连接和锁等待设置上限，避免数据库异常让测试无限阻塞。
    private String url(String database) {
        return "jdbc:mysql://" + env("MYSQL_HOST", "localhost") + ":" + env("MYSQL_PORT", "3306") + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=3000&socketTimeout=10000"
                + "&sessionVariables=innodb_lock_wait_timeout=5";
    }

    // 严格校验本次生成的库名，绝不接受配置的业务库名作为清理目标。
    private String quotedSchema() {
        if (schema == null || !schema.matches("harness_plan_it_[0-9a-f]{32}") || schema.equals(env("MYSQL_DATABASE", "learning_agent"))) {
            throw new IllegalStateException("不安全的计划测试库名");
        }
        return "`" + schema + "`";
    }

    // 测试复用正式建表定义，不另建一份容易过期的测试 SQL。
    private String tableDdl(String table) throws Exception {
        if (!List.of("learning_sessions", "agent_task_plans", "agent_task_steps").contains(table)) throw new IllegalArgumentException("不支持的测试表");
        String schemaSql = new ClassPathResource("db/migration/learningAgentSql.sql").getContentAsString(StandardCharsets.UTF_8);
        var match = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + Pattern.quote(table) + "\\s*\\(.*?;").matcher(schemaSql);
        if (!match.find()) throw new IllegalStateException("缺少计划测试建表定义");
        return match.group();
    }

    // 只读取当前进程的连接配置，不输出密码。
    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    // 用同一用户的两步计划作为并发修改目标。
    private AgentTaskPlan createPlan() {
        CreateTaskPlanRequest request = new CreateTaskPlanRequest();
        request.setGoal("验证并发更新");
        List<CreateTaskStepRequest> steps = new ArrayList<>();
        for (int index = 1; index <= 2; index++) {
            CreateTaskStepRequest step = new CreateTaskStepRequest();
            step.setDescription("步骤" + index);
            step.setCompletionCriteria("完成条件" + index);
            steps.add(step);
        }
        request.setSteps(steps);
        return service.create(UUID.randomUUID().toString(), SESSION_ID, request);
    }

    // 复制已读取的版本和全部步骤，两个并发请求之间不共享可变 DTO。
    private UpdateTaskPlanRequest updateRequest(AgentTaskPlan plan) {
        UpdateTaskPlanRequest request = new UpdateTaskPlanRequest();
        request.setExpectedVersion(plan.getVersion());
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
        request.setSteps(steps);
        return request;
    }
}
