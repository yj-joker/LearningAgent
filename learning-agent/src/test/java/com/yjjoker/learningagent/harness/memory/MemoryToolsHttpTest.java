package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.constant.MessageConstant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

// 显式开启才请求真实模型；随机端口不占用开发后端，新账户避免修改真实用户记忆。
@EnabledIfEnvironmentVariable(named = "MEMORY_HTTP_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "harness.context.max-context-characters=60000", "harness.context.max-tool-result-characters=4000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Slf4j
class MemoryToolsHttpTest {
    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;
    // 只关闭与验收无关的文档轮询，防止测试服务处理用户待解析文档；Agent 和数据库组件全部使用真实实现。
    @MockitoBean private DocumentTaskScheduler documentTaskScheduler;
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> createdSessions = new ArrayList<>();
    private String token;
    private long userId;

    // 通过真正的 HTTP 请求验证两种范围的增改删、召回、计数、防重复及查询不写入。
    @Test
    void shouldRunMemoryLifecycleThroughHttpAndRealAliyun() throws Exception {
        String tag = "httpmem_" + System.currentTimeMillis();
        String password = UUID.randomUUID().toString();
        String userKey = tag + "_preference";
        String sessionKey = tag + "_goal";
        try {
            // 注册和登录都走接口；密码和 token 只留在测试内存，不写入日志。
            post("/learning-agent/user/register", Map.of("username", tag, "password", password));
            JsonNode login = post("/learning-agent/user/login", Map.of("username", tag, "password", password));
            token = login.get("token").asString();
            userId = login.get("id").asLong();
            long courseId = post("/learning-agent/courses/createCourse", Map.of("courseName", tag,
                    "difficultyLevel", 1, "learningOutline", "[]")).get("id").asLong();
            long first = createSession(courseId, tag + "_first");
            log.info("HTTP 记忆验收已开始，port={}，userId={}，sessionId={}，输入仅为合成样本",
                    environment.getProperty("local.server.port"), userId, first);

            chat(first, "请记住我的验收专用学习偏好：我喜欢用蓝色卡片复习；保存在跨会话 USER 记忆，memoryKey 使用 " + userKey + "。", "create_memory");
            Map<String, Object> original = memory("user_memories", "user_id", userId, userKey);
            assertEquals("ACTIVE", original.get("status"));
            assertTrue(original.get("memory_content").toString().contains("蓝色"));
            assertEquals(1, activeCount("user_memories", "user_id", userId));
            assertEquals(0, activeCount("session_memories", "session_id", first));
            assertEquals(1, changes("USER", userId));
            log.info("HTTP 验收通过：USER 新增一次，后置提取未重复写入");

            // 新会话没有原聊天历史，回答必须依靠跨会话记忆，而不是复述上轮消息。
            long second = createSession(courseId, tag + "_second");
            String recalled = chat(second, "请先调用 list_memories 刷新索引，再用 recall_memory 读取验收专用学习偏好的完整正文并告诉我。只查询，不更改记忆。", "recall_memory");
            assertTrue(recalled.contains("蓝色"));
            assertEquals(1, changes("USER", userId));
            log.info("HTTP 验收通过：新会话完成列表刷新和正文召回，查询没有产生写入");

            chat(second, "请修改我的验收专用学习偏好：把蓝色卡片改为绿色卡片，更新现有记忆，不新建。", "update_memory");
            Map<String, Object> updated = memory("user_memories", "user_id", userId, userKey);
            assertEquals(original.get("id"), updated.get("id"));
            assertTrue(updated.get("memory_content").toString().contains("绿色"));
            assertEquals(1, activeCount("user_memories", "user_id", userId));
            assertEquals(2, changes("USER", userId));
            log.info("HTTP 验收通过：USER 修改保留原主键和 key，计数只增加一次");

            chat(second, "请记住本会话的验收目标是练习二分查找；仅保存在当前 SESSION 记忆，memoryKey 使用 " + sessionKey + "。", "create_memory");
            assertEquals(1, activeCount("session_memories", "session_id", second));
            assertEquals(1, changes("SESSION", second));
            long third = createSession(courseId, tag + "_third");
            chat(third, "请调用 list_memories 列出当前可见的记忆，只查询，不变更任何内容。", "list_memories");
            assertFalse(toolResults(third, 0).stream().anyMatch(content -> content.contains(sessionKey)));
            assertEquals(0, activeCount("session_memories", "session_id", third));
            log.info("HTTP 验收通过：SESSION 记忆没有泄露到另一会话");

            // 教学提问和明确的“不操作”要求不能变成工具写入或自动提取删除。
            long beforeQuestion = lastMessage(second);
            JsonNode explanation = post("/agent/chat", Map.of("sessionId", second, "userMessage", "如何删除记忆？只解释流程，不要调用任何写入工具，也不要修改现有记忆。"));
            // 待审批调用不写入可重放工具历史，因此还要检查接口没有产生新的审批批次。
            assertEquals("COMPLETED", explanation.get("status").asString());
            assertTrue(explanation.get("approvals").isEmpty());
            String questionCalls = toolCalls(second, beforeQuestion);
            assertFalse(questionCalls.contains("create_memory") || questionCalls.contains("update_memory") || questionCalls.contains("delete_memory"));
            assertEquals(2, changes("USER", userId));
            assertEquals(1, changes("SESSION", second));
            assertEquals(1, activeCount("user_memories", "user_id", userId));
            assertEquals(1, activeCount("session_memories", "session_id", second));
            log.info("HTTP 验收通过：解释性提问没有修改现有记忆");

            chat(second, "请修改本会话的验收目标，把练习二分查找改成练习归并排序，保持 SESSION 范围，不新建。", "update_memory");
            assertTrue(memory("session_memories", "session_id", second, sessionKey).get("memory_content").toString().contains("归并排序"));
            assertEquals(2, changes("SESSION", second));
            chat(second, "请删除本会话的验收目标记忆，只删除 SESSION 中的目标，保留用户学习偏好。", "delete_memory");
            assertEquals("DELETED", memory("session_memories", "session_id", second, sessionKey).get("status"));
            assertEquals(0, activeCount("session_memories", "session_id", second));
            assertEquals(3, changes("SESSION", second));
            assertEquals(1, activeCount("user_memories", "user_id", userId));

            chat(second, "请忘记我的验收专用学习偏好，删除跨会话的卡片偏好记忆。", "delete_memory");
            assertEquals("DELETED", memory("user_memories", "user_id", userId, userKey).get("status"));
            assertEquals(0, activeCount("user_memories", "user_id", userId));
            assertEquals(3, changes("USER", userId));
            chat(third, "请调用 list_memories 查看现在仍有效的记忆，只查询，不更改。", "list_memories");
            assertEquals(0, activeCount("user_memories", "user_id", userId));
            assertEquals(0, activeCount("session_memories", "session_id", second));
            log.info("HTTP 验收全部通过：两类记忆各新增、修改、假删除一次；删除后没有被自动提取重新添加");
        } finally {
            // 只关闭本测试创建的会话；保留假删除记录和聊天轨迹供用户复查。
            for (Long sessionId : createdSessions) {
                try {
                    HttpRequest request = request("/agent/delete/" + sessionId).DELETE().build();
                    http.send(request, HttpResponse.BodyHandlers.discarding());
                } catch (Exception exception) {
                    log.warn("测试会话未能关闭，sessionId={}，exceptionType={}", sessionId, exception.getClass().getSimpleName());
                }
            }
        }
    }

    // 新建专属会话，不借用用户已有的学习会话。
    private long createSession(long courseId, String title) throws Exception {
        long id = post("/learning-agent/learning/session", Map.of("courseId", courseId, "sessionTitle", title)).get("id").asLong();
        createdSessions.add(id);
        return id;
    }

    // 写工具先核对待审批状态再批准；读工具仍核对实际请求和成功结果。
    private String chat(long sessionId, String message, String expectedTool) throws Exception {
        long before = lastMessage(sessionId);
        JsonNode response = post("/agent/chat", Map.of("sessionId", sessionId, "userMessage", message));
        String answer = response.get("answer").asString();
        if (List.of("create_memory", "update_memory", "delete_memory").contains(expectedTool)) {
            // 这里只批准本次合成测试返回的申请，不能处理测试账户的其他历史申请。
            return approveReturnedBatch(response, sessionId, expectedTool);
        }
        assertEquals("COMPLETED", response.get("status").asString());
        assertTrue(toolCalls(sessionId, before).contains(expectedTool), "本轮没有调用预期工具：" + expectedTool);
        assertTrue(toolResults(sessionId, before).stream().map(json::readTree)
                .anyMatch(result -> result.get("success") != null && result.get("success").asBoolean()));
        Integer replayable = jdbc.queryForObject("SELECT COUNT(*) FROM learning_session_messages WHERE session_id=? AND id>? AND role='TOOL' AND context_replayable=TRUE",
                Integer.class, sessionId, before);
        assertEquals(0, replayable);
        log.info("HTTP 工具链路通过，sessionId={}，expectedTool={}，answerCharacters={}", sessionId, expectedTool, answer.length());
        return answer;
    }

    // 真实 HTTP 测试完成审批后必须 resume；只批准还不能宣称记忆工具已执行。
    private String approveReturnedBatch(JsonNode response, long sessionId, String expectedTool) throws Exception {
        assertEquals("WAITING_APPROVAL", response.get("status").asString());
        String runId = response.get("runId").asString();
        Map<String, Object> run = jdbc.queryForMap("SELECT status, checkpoint_json FROM agent_approval_runs WHERE run_id=? AND user_id=? AND session_id=?",
                runId, userId, sessionId);
        assertEquals("WAITING_APPROVAL", run.get("status"));
        assertTrue(run.get("checkpoint_json").toString().contains(expectedTool));
        JsonNode requests = response.get("approvals");
        assertTrue(requests.isArray() && !requests.isEmpty());
        for (JsonNode approval : requests) {
            assertEquals(runId, approval.get("runId").asString());
            assertEquals("PENDING", approval.get("status").asString());
            post("/agent/runs/" + runId + "/approvals/" + response.get("batchNumber").asInt()
                    + "/" + approval.get("toolCallId").asString(), Map.of("approved", true));
        }
        JsonNode resumed = post("/agent/runs/" + runId + "/resume", Map.of());
        assertEquals("COMPLETED", resumed.get("status").asString());
        String status = jdbc.queryForObject("SELECT status FROM agent_approval_runs WHERE run_id=? AND user_id=?", String.class, runId, userId);
        assertEquals("COMPLETED", status);
        log.info("HTTP 审批与恢复完成，runId={}，sessionId={}，expectedTool={}，approvalCount={}",
                runId, sessionId, expectedTool, requests.size());
        return resumed.get("answer").asString();
    }

    // 参数化查询只读取当前合成用户或会话的指定 key，不扫描真实用户正文。
    private Map<String, Object> memory(String table, String owner, long ownerId, String key) {
        return jdbc.queryForMap("SELECT id, status, memory_content FROM " + table + " WHERE " + owner + "=? AND memory_key=?", ownerId, key);
    }

    // 有效数量能检测删除后的同义 key 重建，不能只核对原 key 的状态。
    private int activeCount(String table, String owner, long ownerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + owner + "=? AND status='ACTIVE'", Integer.class, ownerId);
    }

    // 真实变更计数用于证明工具和后置提取没有重复处理同一次请求。
    private long changes(String scope, long ownerId) {
        return jdbc.queryForObject("SELECT change_count FROM memory_consolidation_state WHERE scope=? AND owner_id=?", Long.class, scope, ownerId);
    }

    // 以数据库消息位置划分本轮，避免误把上一轮工具调用当成本轮证据。
    private long lastMessage(long sessionId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM learning_session_messages WHERE session_id=?", Long.class, sessionId);
    }

    // 只检查指定测试会话新增的工具请求，不输出完整工具参数。
    private String toolCalls(long sessionId, long after) {
        return String.join("\n", jdbc.queryForList("SELECT tool_calls FROM learning_session_messages WHERE session_id=? AND id>? AND tool_calls IS NOT NULL", String.class, sessionId, after));
    }

    // 读取实际持久化的工具结果，在测试内检查而不是打印敏感内容。
    private List<String> toolResults(long sessionId, long after) {
        return jdbc.queryForList("SELECT content FROM learning_session_messages WHERE session_id=? AND id>? AND role='TOOL'", String.class, sessionId, after);
    }

    // 所有写入通过 Controller；数据库连接只用于核对验收结果。
    private JsonNode post(String path, Object body) throws Exception {
        HttpResponse<String> response = http.send(request(path).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "HTTP 状态不符合预期：" + path);
        JsonNode root = json.readTree(response.body());
        assertEquals(MessageConstant.SUCCESS_CODE, root.get("code").asString(), "接口执行失败：" + path + "；" + root.get("message").asString());
        return root.get("data");
    }

    // 请求发往测试服务随机端口；鉴权头不落盘、不输出。
    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
                .timeout(Duration.ofMinutes(3)).header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }
}
