package com.yjjoker.learningagent.harness.learningplan;

import com.yjjoker.learningagent.client.AliyunLlmClient;
import com.yjjoker.learningagent.config.DocumentTaskRecoveryRunner;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.memory.service.MemoryExtractionService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

// 显式开启后才调用真实 Aliyun；每个样本使用独立账号、计划和 HTTP 会话。
@EnabledIfEnvironmentVariable(named = "LEARNING_TEACHING_HTTP_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "milvus.enabled=false", "harness.memory.consolidation.enabled=false",
        "harness.context.max-context-characters=60000", "harness.context.max-tool-result-characters=4000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Slf4j
class LearningTeachingHttpAliyunTest {
    private static final String SKILL_NAME = "learning-teaching";
    private static final String SKILL_BODY_MARKER = "CONFIRMED 申请必须同时包含用户自己的解释和一道独立练习证据";
    private static final Set<String> APPROVABLE_TOOLS = Set.of(
            "propose_learning_progress", "create_session_goal", "switch_session_goal",
            "update_task_progress", "update_task_plan");
    private static final List<Stage> STAGES = List.of(
            new Stage("Collection：List 与 Set", "能说明二者在顺序和重复元素上的差异，并举例解释选择理由"),
            new Stage("Map：键值映射与查找", "能解释 key 与 value 的关系、key 唯一性，并举例说明如何更新和查询"));

    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;
    // 后台文档任务与学习闭环无关，测试中关闭它们的轮询和恢复。
    @MockitoBean private DocumentTaskScheduler documentTaskScheduler;
    @MockitoBean private DocumentTaskRecoveryRunner documentTaskRecoveryRunner;
    // 记忆提取不是本阶段验收目标，返回空候选以隔离其模型请求和审批副作用。
    @MockitoBean private MemoryExtractionService memoryExtractionService;
    // Spy 只旁路记录主模型系统提示词，真实模型请求仍由 callRealMethod 发出。
    @MockitoSpyBean private AliyunLlmClient llm;

    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> observedSystemPrompts = new CopyOnWriteArrayList<>();
    private String token;
    private String username;
    private String password;
    private Instant tokenIssuedAt;
    private long userId;
    private long sessionId;
    private String draftRef;
    private boolean prematureConfirmationObserved;

    // 多次完整跑完计划；每个样本报告成功或失败，避免首个模型偏差掩盖其余样本结果。
    // 真实 Aliyun 延迟、超时和 JWT 刷新失败只记录为样本失败原因，不当作数据库闭环已经通过。
    @Test
    void shouldCompleteEveryStageThroughRealHttpAliyunAndApproval() {
        observeMainModelPrompts();
        doReturn(List.of()).when(memoryExtractionService).extract(any(), anyString(), anyString());

        int sampleCount = requestedSampleCount();
        int attemptedCount = 0;
        int successCount = 0;
        List<String> failures = new ArrayList<>();
        log.info("学习掌握闭环真实验收开始，port={}，sampleCount={}，stageCount={}，model={}，只使用合成内容",
                environment.getProperty("local.server.port"), sampleCount, STAGES.size(),
                environment.getProperty("aliyun.llm.model", "configured"));

        for (int sample = 1; sample <= sampleCount; sample++) {
            String phase = "创建合成用户、计划和会话";
            attemptedCount++;
            prematureConfirmationObserved = false;
            try {
                prepareSample(sample);
                log.info("学习闭环样本开始，sample={}/{}，userId={}，sessionId={}，draftRef={}",
                        sample, sampleCount, userId, sessionId, draftRef);

                for (int stageIndex = 0; stageIndex < STAGES.size(); stageIndex++) {
                    Stage stage = STAGES.get(stageIndex);
                    int currentStage = stageIndex + 1;
                    int promptStart = observedSystemPrompts.size();

                    phase = "阶段" + currentStage + "教学和 Skill 加载";
                    long beforeTeaching = lastMessageId();
                    JsonNode teaching = chatAndResolveApprovals(
                            "请按学习计划教学当前第" + currentStage + "个未确认阶段“" + stage.title()
                                    + "”。先讲解，再给我一道独立练习。",
                            "阶段" + currentStage + "教学", false);
                    assertFalse(teaching.path("answer").asString().isBlank(), "教学回答不能为空");
                    assertFalse(prematureConfirmationObserved,
                            "用户尚未回答练习前，模型不应申请确认掌握");

                    phase = "阶段" + currentStage + "证据不足分支";
                    JsonNode weakResponse = chatAndResolveApprovals(weakStudentEvidence(currentStage),
                            "阶段" + currentStage + "证据不足", false);
                    assertFalse(weakResponse.path("answer").asString().isBlank(), "证据不足后应继续回应或教学");
                    assertFalse(prematureConfirmationObserved, "证据不足时模型申请了确认掌握");
                    JsonNode weakProgress = readProgress();
                    assertFalse("CONFIRMED".equals(stageStatus(weakProgress, currentStage)),
                            "证据不足不能把长期阶段写成 CONFIRMED");

                    phase = "阶段" + currentStage + "掌握证据与审批";
                    boolean confirmed = false;
                    for (int answerAttempt = 1; answerAttempt <= 3 && !confirmed; answerAttempt++) {
                        long beforeAnswer = lastMessageId();
                        JsonNode response = chatAndResolveApprovals(studentEvidence(currentStage),
                                "阶段" + currentStage + "学生回答" + answerAttempt, true);
                        JsonNode progress = readProgress();
                        String status = stageStatus(progress, currentStage);
                        log.info("阶段证据检查完成，sample={}，stage={}，answerAttempt={}，status={}，progressVersion={}",
                                sample, currentStage, answerAttempt, status,
                                progress.path("steps").path(stageIndex).path("progressVersion").asLong());
                        confirmed = "CONFIRMED".equals(status);
                        if (!confirmed && response.path("answer").asString().isBlank()) {
                            throw new AssertionError("模型没有给出继续教学或点评");
                        }
                        if (!confirmed && answerAttempt < 3) {
                            log.info("阶段尚未确认，继续提交补充证据，sample={}，stage={}，attempt={}，messageBoundary={} ",
                                    sample, currentStage, answerAttempt, beforeAnswer);
                        }
                    }
                    assertTrue(confirmed, "模型未在三轮学生回答内完成阶段审批闭环：stage=" + currentStage);

                    phase = "核对阶段证据和下一轮快照";
                    JsonNode savedProgress = readProgress();
                    JsonNode savedStage = savedProgress.path("steps").path(stageIndex);
                    assertEquals("CONFIRMED", savedStage.path("status").asString());
                    assertEquals("BOTH", savedStage.path("evidenceType").asString());
                    assertFalse(savedStage.path("evidenceSummary").asString().isBlank());
                    assertFalse(savedStage.path("assessmentReason").asString().isBlank());

                    if (stageIndex > 0) {
                        boolean previousStageWasVisible = observedSystemPrompts.subList(promptStart,
                                        observedSystemPrompts.size()).stream()
                                .anyMatch(prompt -> prompt.contains("1. [CONFIRMED]"));
                        assertTrue(previousStageWasVisible,
                                "下一阶段请求没有从数据库加载上一个已确认阶段的状态");
                    }

                    String newToolCalls = toolCallsAfter(beforeTeaching);
                    assertTrue(newToolCalls.contains("propose_learning_progress"),
                            "阶段进度没有通过 propose_learning_progress 工具变更");
                    assertTrue(newToolCalls.contains("load_skill") && newToolCalls.contains(SKILL_NAME),
                            "本轮没有从技能索引调用 load_skill");
                    boolean skillIndexVisible = observedSystemPrompts.subList(promptStart,
                                    observedSystemPrompts.size()).stream()
                            .anyMatch(prompt -> prompt.contains(SKILL_NAME));
                    assertTrue(skillIndexVisible, "主模型请求没有收到 learning-teaching 技能索引");
                    boolean skillBodyLoaded = observedSystemPrompts.subList(promptStart,
                                    observedSystemPrompts.size()).stream()
                            .anyMatch(prompt -> prompt.contains(SKILL_BODY_MARKER));
                    assertTrue(skillBodyLoaded,
                            "主模型没有在 load_skill 后收到 learning-teaching 正文");
                    log.info("学习阶段真实闭环通过，sample={}，stage={}，status=CONFIRMED，evidenceType=BOTH，skillLoaded=true",
                            sample, currentStage);
                }

                phase = "核对整个长期计划";
                JsonNode finalProgress = readProgress();
                for (int index = 0; index < STAGES.size(); index++) {
                    assertEquals("CONFIRMED", stageStatus(finalProgress, index + 1),
                            "长期计划仍有未确认阶段：" + (index + 1));
                }
                successCount++;
                log.info("学习计划样本完整通过，sample={}/{}，userId={}，sessionId={}，confirmedStages={}",
                        sample, sampleCount, userId, sessionId, STAGES.size());
            } catch (Exception | AssertionError exception) {
                String failure = "sample=" + sample + ", phase=" + phase + ", errorType="
                        + exception.getClass().getSimpleName() + ", detail=" + safeFailureMessage(exception);
                failures.add(failure);
                log.warn("学习计划样本未通过，{}，userId={}，sessionId={}，数据保留供复查",
                        failure, userId, sessionId);
                if (isAuthenticationFailure(exception)) {
                    log.error("Aliyun 鉴权失败，停止后续样本以免重复发送无效请求");
                    break;
                }
            }
        }

        double passRate = attemptedCount == 0 ? 0 : (double) successCount / attemptedCount;
        log.info("学习掌握闭环多会话验收结束，passed={}，attempted={}，requested={}，passRate={}%，failures={}",
                successCount, attemptedCount, sampleCount, Math.round(passRate * 100), failures.size());
        assertEquals(sampleCount, successCount,
                "真实学习闭环通过率=" + Math.round(passRate * 100) + "%；失败样本=" + failures);
    }

    // 创建独立合成用户，正式激活两阶段计划，再通过 HTTP 绑定到专注会话。
    private void prepareSample(int sample) throws Exception {
        String tag = "teach_" + System.currentTimeMillis() + "_" + sample + "_"
                + UUID.randomUUID().toString().substring(0, 6);
        username = tag;
        password = UUID.randomUUID().toString();
        post("/learning-agent/user/register", Map.of("username", username, "password", password));
        JsonNode login = post("/learning-agent/user/login", Map.of("username", username, "password", password));
        token = login.path("token").asString();
        tokenIssuedAt = Instant.now();
        userId = login.path("id").asLong();

        long courseId = post("/learning-agent/courses/createCourse", Map.of("courseName", tag,
                "difficultyLevel", 1, "learningOutline", "[]")).path("id").asLong();
        List<Map<String, String>> steps = STAGES.stream().map(stage -> Map.of(
                "description", stage.title(), "completionCriteria", stage.criteria())).toList();
        JsonNode draft = post("/learning-agent/learning-plans/drafts", Map.of(
                "title", "Java 集合学习闭环 " + sample,
                "objective", "理解常用集合并能按实际问题选择数据结构",
                "learnerProfile", "正在学习 Java 基础",
                "weeklyCommitment", "每周投入 3 小时",
                "constraints", "以解释和独立练习作为阶段证据",
                "steps", steps));
        draftRef = draft.path("draftRef").asString();
        post("/learning-agent/learning-plans/drafts/" + draftRef + "/activate",
                Map.of("expectedVersion", draft.path("version").asLong()));

        sessionId = post("/learning-agent/learning/session", Map.of("courseId", courseId,
                "sessionTitle", "Java 学习闭环 " + sample, "status", "ACTIVE")).path("id").asLong();
        put("/agent/sessions/" + sessionId + "/learning-plan",
                Map.of("draftRef", draftRef, "expectedBindingVersion", 0));
        JsonNode progress = readProgress();
        assertEquals(STAGES.size(), progress.path("steps").size(), "数据库计划步骤数量不一致");
        log.info("学习计划测试数据已准备，sample={}，userId={}，sessionId={}，draftRef={}，stageCount={}",
                sample, userId, sessionId, draftRef, progress.path("steps").size());
    }

    // 每个请求都通过聊天接口；任何审批都先由测试模拟用户决策，再恢复原 AgentLoop。
    private JsonNode chatAndResolveApprovals(String message, String phase, boolean allowConfirmation) throws Exception {
        JsonNode response = post("/agent/chat", Map.of("sessionId", sessionId,
                "mode", "FOCUS", "userMessage", message));
        int resumeCount = 0;
        while ("WAITING_APPROVAL".equals(response.path("status").asString())) {
            // 测试审批恢复上限与生产工具轮数保持一致，避免测试在第九批时提前制造失败。
            if (++resumeCount > 15) throw new AssertionError("审批批次超过测试上限：" + phase);
            String runId = response.path("runId").asString();
            int batchNumber = response.path("batchNumber").asInt();
            JsonNode approvals = response.path("approvals");
            assertTrue(approvals.isArray() && !approvals.isEmpty(), "等待状态没有审批申请");
            for (JsonNode approval : approvals) {
                String toolName = approval.path("toolName").asString();
                assertTrue(APPROVABLE_TOOLS.contains(toolName),
                        "测试不会自动批准本闭环之外的工具：" + toolName);
                boolean approved = true;
                if ("propose_learning_progress".equals(toolName)) {
                    JsonNode arguments = json.readTree(approval.path("arguments").asString());
                    String targetStatus = arguments.path("targetStatus").asString();
                    if ("CONFIRMED".equals(targetStatus) && !allowConfirmation) {
                        // 弱证据或尚未回答练习时，模拟用户拒绝不合时机的掌握申请。
                        approved = false;
                        prematureConfirmationObserved = true;
                    }
                }
                post("/agent/runs/" + runId + "/approvals/" + batchNumber + "/"
                                + approval.path("toolCallId").asString(),
                        Map.of("approved", approved, "reason", approved
                                ? "合成学习闭环测试批准" : "证据不足，不批准掌握确认"));
                log.info("合成用户已处理学习工具审批，phase={}，runId={}，batch={}，toolName={}，approved={}",
                        phase, runId, batchNumber, toolName, approved);
            }
            response = post("/agent/runs/" + runId + "/resume", Map.of());
        }
        assertEquals("COMPLETED", response.path("status").asString(),
                "AgentLoop 没有正常结束本次教学交互：" + phase);
        return response;
    }

    // 回答同时包含自己的概念解释和独立场景，后端才能核对 BOTH 类型证据。
    private String studentEvidence(int stage) {
        if (stage == 1) {
            return "我的解释：List 保留元素顺序并允许重复，适合保留完整报名记录；Set 用于保存唯一元素，适合去重。"
                    + "独立练习：报名名单里同一个姓名可能出现两次，要保留两条报名记录选 List；只统计唯一姓名选 Set。";
        }
        return "我的解释：Map 保存 key 到 value 的对应关系，key 唯一，可以用 key 找到或更新对应 value。"
                + "独立练习：统计单词次数时用单词作 key、次数作 value；读取次数调用 get，出现一次就把 value 加一。";
    }

    // 故意给出概念和练习都错误的回答，检查模型会不会过早申请掌握确认。
    private String weakStudentEvidence(int stage) {
        if (stage == 1) {
            return "我的解释：List 和 Set 都会保留顺序并允许重复，差别只是写法。"
                    + "独立练习：报名系统要保留重复报名记录，我选 Set，因为它会保留重复值。";
        }
        return "我的解释：Map 的 key 可以重复，value 是按下标访问的。"
                + "独立练习：统计词频时把次数作 key，把单词作 value。";
    }

    // 读取长期进度接口的数据库视图，不根据模型自然语言推断阶段状态。
    private JsonNode readProgress() throws Exception {
        return get("/learning-agent/learning-plans/drafts/" + draftRef + "/progress");
    }

    // 所有状态读取也经过 Controller，直接从 HTTP 响应核对当前用户的计划进度。
    private JsonNode get(String path) throws Exception {
        return send(request(path).GET().build(), path);
    }

    // 按计划位置读取状态；缺少位置时测试直接失败，避免把空值当成未完成。
    private String stageStatus(JsonNode progress, int position) {
        JsonNode steps = progress.path("steps");
        assertTrue(steps.isArray() && steps.size() >= position, "长期计划缺少预期阶段：" + position);
        return steps.path(position - 1).path("status").asString();
    }

    // 用审批后的消息边界核对本轮真实工具轨迹，不只检查回答文本。
    private String toolCallsAfter(long messageId) {
        List<String> calls = jdbc.queryForList("SELECT tool_calls FROM learning_session_messages "
                        + "WHERE session_id=? AND id>? AND tool_calls IS NOT NULL ORDER BY id",
                String.class, sessionId, messageId);
        return String.join("\n", calls);
    }

    // 读取测试专属会话的最新消息位置，区分相邻教学交互的工具轨迹。
    private long lastMessageId() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM learning_session_messages WHERE session_id=?",
                Long.class, sessionId);
    }

    // 旁路收集主模型系统消息，确认技能正文只在 load_skill 成功后的请求出现。
    private void observeMainModelPrompts() {
        doAnswer(invocation -> {
            List<LlmMessage> messages = invocation.getArgument(0);
            if (!messages.isEmpty() && messages.getFirst().getContent() != null) {
                observedSystemPrompts.add(messages.getFirst().getContent());
            }
            return invocation.callRealMethod();
        }).when(llm).generate(anyList());
    }

    // 用 HTTP PUT 更新当前测试会话的长期计划关联。
    private JsonNode put(String path, Object body) throws Exception {
        HttpRequest.Builder builder = request(path).PUT(
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return send(builder.build(), path);
    }

    // 所有创建、聊天、审批和查询都经过应用 Controller；JDBC 只读验收轨迹。
    private JsonNode post(String path, Object body) throws Exception {
        HttpRequest request = request(path).POST(
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return send(request, path);
    }

    // 统一检查 HTTP 和业务响应；错误信息不包含认证头或密钥。
    private JsonNode send(HttpRequest request, String path) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "HTTP 请求失败：" + path);
        JsonNode root = json.readTree(response.body());
        assertEquals("200", root.path("code").asString(),
                "业务请求失败：" + path + "，message=" + root.path("message").asString());
        return root.path("data");
    }

    // 构造随机端口请求；token 只放在内存中的请求头，不进入日志或断言文本。
    private HttpRequest.Builder request(String path) throws Exception {
        refreshTokenIfNeeded(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + path))
                .timeout(Duration.ofMinutes(3)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    // 多轮真实模型调用可能超过 JWT 有效期，测试每两分钟用合成账号重新登录。
    private void refreshTokenIfNeeded(String path) throws Exception {
        if (path.endsWith("/register") || path.endsWith("/login")) return;
        if (token != null && tokenIssuedAt != null
                && Duration.between(tokenIssuedAt, Instant.now()).compareTo(Duration.ofMinutes(2)) < 0) {
            return;
        }
        HttpRequest loginRequest = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/learning-agent/user/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("username", username, "password", password))))
                .build();
        HttpResponse<String> response = http.send(loginRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "合成测试用户重新登录失败");
        JsonNode root = json.readTree(response.body());
        assertEquals("200", root.path("code").asString(), "合成测试用户登录业务失败");
        token = root.path("data").path("token").asString();
        tokenIssuedAt = Instant.now();
        assertFalse(token.isBlank(), "重新登录没有返回 token");
        log.info("学习闭环测试凭据已刷新，userId={}，sessionId={}", userId, sessionId);
    }

    // 默认跑三个独立计划；该变量只用于本地缩短调试，不允许意外启动无限样本。
    private int requestedSampleCount() {
        String configured = System.getenv("LEARNING_TEACHING_HTTP_TEST_RUNS");
        int count = configured == null || configured.isBlank() ? 3 : Integer.parseInt(configured);
        if (count < 1 || count > 5) throw new IllegalArgumentException("样本数必须在 1 到 5 之间");
        return count;
    }

    // 只给日志使用异常类型和短消息，防止输出模型完整回答或请求头。
    private String safeFailureMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "无附加信息";
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.substring(0, Math.min(180, normalized.length()));
    }

    // 鉴权配置错误不会因重复创建更多会话而修复，因此停止后续样本。
    private boolean isAuthenticationFailure(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("鉴权失败") || message.contains("LLM_AUTHENTICATION_FAILED"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // 每个学习阶段由标题和可检查的完成条件组成，与正式计划表里的阶段一一对应。
    private static final class Stage {
        private final String title;
        private final String criteria;

        private Stage(String title, String criteria) {
            this.title = title;
            this.criteria = criteria;
        }

        private String title() {
            return title;
        }

        private String criteria() {
            return criteria;
        }
    }
}
