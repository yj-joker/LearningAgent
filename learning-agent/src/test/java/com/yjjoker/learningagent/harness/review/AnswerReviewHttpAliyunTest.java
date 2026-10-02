package com.yjjoker.learningagent.harness.review;

import com.yjjoker.learningagent.client.AliyunLlmClient;
import com.yjjoker.learningagent.config.DocumentTaskRecoveryRunner;
import com.yjjoker.learningagent.harness.hook.FinalAnswerConsistencyHook;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import com.yjjoker.learningagent.service.impl.DocumentTaskScheduler;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

// 显式开启才产生模型费用；独立合成账号、随机端口，不使用真实用户的会话或记忆。
@EnabledIfEnvironmentVariable(named = "ANSWER_REVIEW_HTTP_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "milvus.enabled=false", "harness.memory.consolidation.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Slf4j
class AnswerReviewHttpAliyunTest {
    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;
    // 测试实例不接管用户的文档任务，Agent、审批和数据库服务仍使用真实实现。
    @MockitoBean private DocumentTaskScheduler documentTaskScheduler;
    @MockitoBean private DocumentTaskRecoveryRunner documentTaskRecoveryRunner;
    // Spy 只观察审查调用，callRealMethod 保证主模型、审查和提取仍访问真实 Aliyun。
    @MockitoSpyBean private AliyunLlmClient llm;
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<JsonNode> reviews = new CopyOnWriteArrayList<>();
    private String token;
    private long userId;
    private long sessionId;

    // 通过 HTTP 验证讲解、批准、拒绝和只读查询，并用数据库核对实际状态。
    @Test
    void shouldReviewApprovedAndRejectedProgressThroughRealHttp() throws Exception {
        observeSyntheticReviews();
        String tag = "review_" + System.currentTimeMillis();
        String password = UUID.randomUUID().toString();
        // 凭据只保留在内存，诊断日志只涉及下面创建的合成学习内容。
        post("/learning-agent/user/register", Map.of("username", tag, "password", password));
        JsonNode login = post("/learning-agent/user/login", Map.of("username", tag, "password", password));
        token = login.path("token").asString();
        userId = login.path("id").asLong();
        long courseId = post("/learning-agent/courses/createCourse", Map.of("courseName", tag,
                "difficultyLevel", 1, "learningOutline", "[]")).path("id").asLong();
        sessionId = post("/learning-agent/learning/session", Map.of("courseId", courseId,
                "sessionTitle", tag, "status", "ACTIVE")).path("id").asLong();
        log.info("真实 HTTP 审查验收开始，port={}，userId={}，sessionId={}，仅使用合成内容",
                environment.getProperty("local.server.port"), userId, sessionId);
        try {
            // 普通讲解不能凭助手自己讲过了，就把学习步骤标记为已完成。
            JsonNode taught = chat("给我简单讲解 Java 集合的整体分类，只解释 Collection 与 Map 的区别，先不要更改任何步骤状态。");
            assertNormalAnswer(taught);
            assertEquals("PENDING", firstStepStatus());
            assertEquals(0, approvalsInSession());
            log.info("HTTP 验收通过：普通讲解未修改步骤或创建审批，sessionId={}", sessionId);

            // 申请开始学习，先验证待审批时数据库仍未变化。
            JsonNode waiting = chat("请提交申请，把当前计划第一步设为进行中 IN_PROGRESS，其他步骤保持不变；我会在审批卡里确认。只申请开始，不申请完成。");
            assertProgressApproval(waiting);
            assertEquals("PENDING", firstStepStatus());
            int beforeApprovedReview = reviews.size();
            JsonNode approved = decideAndResume(waiting, true);
            assertNormalAnswer(approved);
            assertEquals("IN_PROGRESS", firstStepStatus());
            assertTrue(reviews.size() > beforeApprovedReview, "审批恢复后必须实际调用审查模型");
            assertEquals("PASS", reviews.getLast().path("output").path("action").asString());
            assertClearedCheckpoint(waiting.path("runId").asString());
            log.info("HTTP 验收通过：批准后数据库为 IN_PROGRESS，审查正常放行，runId={}", waiting.path("runId").asString());

            // 用户拒绝完成申请，不能再次申请或把普通文字说成完成。
            JsonNode completion = chat("我确认第一步通过，请只申请把第一步改为 COMPLETED，记录是用户主动确认，不代表独立测验通过；其他步骤不变。");
            assertProgressApproval(completion);
            int approvalsBeforeReject = approvalsInSession();
            JsonNode rejected = decideAndResume(completion, false);
            assertNormalAnswer(rejected);
            assertEquals("IN_PROGRESS", firstStepStatus());
            assertEquals(approvalsBeforeReject, approvalsInSession(), "拒绝后不能自动产生另一份审批");
            assertEquals("PASS", reviews.getLast().path("output").path("action").asString());
            assertFalse(reviews.getLast().path("input").path("continuationAllowed").asBoolean());
            assertClearedCheckpoint(completion.path("runId").asString());
            log.info("HTTP 验收通过：拒绝后状态不变、没有重复审批、回答通过审查，runId={}", completion.path("runId").asString());

            // 下一次查询从当前数据库读取，不继承之前草稿里的完成说法。
            JsonNode queried = chat("只查询当前步骤进度，不申请任何修改：第一步现在是什么状态？");
            assertNormalAnswer(queried);
            assertEquals("IN_PROGRESS", firstStepStatus());
            assertEquals(approvalsBeforeReject, approvalsInSession());
            log.info("真实 HTTP 审查验收全部通过，sessionId={}，reviewCalls={}", sessionId, reviews.size());
        } finally {
            // 只输出本文件合成账号的审查草稿与理由；生产服务不增加正文日志开关。
            for (int index = 0; index < reviews.size(); index++) {
                JsonNode review = reviews.get(index);
                log.info("合成审查诊断，index={}，draft={}，progress={}，tools={}，output={}", index,
                        review.path("input").path("draftAnswer"), review.path("input").path("databaseProgress"),
                        review.path("input").path("toolExecutions"), review.path("output"));
            }
            // 保留精确测试编号和数据库证据供复查，不删除用户数据或干扰原开发服务。
            log.info("合成验收数据已保留，userId={}，sessionId={}", userId, sessionId);
        }
    }

    // 只旁路采集审查专用请求；观察器不替换模型结果，不捕获密钥和请求头。
    private void observeSyntheticReviews() {
        doAnswer(invocation -> {
            List<LlmMessage> messages = invocation.getArgument(0);
            Object result = invocation.callRealMethod();
            if (AgentSystemPrompt.ANSWER_REVIEW_PROMPT.equals(messages.getFirst().getContent())
                    && result instanceof TextLlmResponse text) {
                var review = json.createObjectNode();
                review.set("input", json.readTree(messages.getLast().getContent()));
                review.set("output", json.readTree(text.content()));
                reviews.add(review);
            }
            return result;
        }).when(llm).generateWithoutTools(anyList());
    }

    // 每轮使用同一个专属会话，验证多轮历史和审批恢复，而非孤立的模型提示测试。
    private JsonNode chat(String message) throws Exception {
        return post("/agent/chat", Map.of("sessionId", sessionId, "mode", "FOCUS", "userMessage", message));
    }

    // 只允许测试预期的进度审批，不能无条件批准模型返回的任意工具。
    private void assertProgressApproval(JsonNode response) {
        assertEquals("WAITING_APPROVAL", response.path("status").asString(), response.toString());
        assertEquals(1, response.path("approvals").size());
        assertEquals("update_task_progress", response.path("approvals").get(0).path("toolName").asString());
    }

    // 批准或拒绝后仍须恢复原任务；决定接口本身不执行工具。
    private JsonNode decideAndResume(JsonNode waiting, boolean approved) throws Exception {
        String runId = waiting.path("runId").asString();
        String callId = waiting.path("approvals").get(0).path("toolCallId").asString();
        post("/agent/runs/" + runId + "/approvals/" + waiting.path("batchNumber").asInt() + "/" + callId,
                Map.of("approved", approved, "reason", approved ? "合成测试确认开始" : "合成测试拒绝完成，保留原状态"));
        return post("/agent/runs/" + runId + "/resume", Map.of());
    }

    // 返回 COMPLETED 不代表审查成功，还必须排除后端的保护性回答。
    private void assertNormalAnswer(JsonNode response) {
        assertEquals("COMPLETED", response.path("status").asString());
        assertNotEquals(FinalAnswerConsistencyHook.SAFE_ANSWER, response.path("answer").asString());
        assertFalse(response.path("answer").asString().isBlank());
        assertTrue(response.path("approvals").isEmpty());
    }

    // 直接核对当前目标的第一步，避免只根据模型的回答判断写入是否成功。
    private String firstStepStatus() {
        return jdbc.queryForObject("SELECT s.status FROM agent_task_steps s JOIN agent_session_focus f "
                + "ON s.plan_id=f.active_plan_id WHERE f.session_id=? AND f.user_id=? AND s.position=1",
                String.class, sessionId, userId);
    }

    // 精确统计合成会话的审批行，用于识别拒绝后重复申请。
    private int approvalsInSession() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_approvals a JOIN agent_approval_runs r "
                + "ON a.run_id=r.run_id WHERE r.session_id=? AND r.user_id=?", Integer.class, sessionId, userId);
    }

    // 完成后清理检查点正文，同时保留运行状态与结果供重复读取。
    private void assertClearedCheckpoint(String runId) {
        String checkpoint = jdbc.queryForObject("SELECT checkpoint_json FROM agent_approval_runs WHERE run_id=? AND user_id=?",
                String.class, runId, userId);
        assertTrue(json.readTree(checkpoint).isEmpty());
    }

    // 所有业务写入通过 HTTP；断言失败只输出合成业务响应，不输出认证信息。
    private JsonNode post(String path, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port") + path))
                .timeout(Duration.ofMinutes(3)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "HTTP 请求失败：" + path);
        JsonNode root = json.readTree(response.body());
        assertEquals("200", root.path("code").asString(), "业务请求失败：" + path + "；" + root.path("message").asString());
        return root.path("data");
    }
}
