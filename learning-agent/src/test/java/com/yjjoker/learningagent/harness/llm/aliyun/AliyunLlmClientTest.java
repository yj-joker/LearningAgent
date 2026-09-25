package com.yjjoker.learningagent.harness.llm.aliyun;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yjjoker.learningagent.client.AliyunLlmClient;
import com.yjjoker.learningagent.config.AliyunLlmProperties;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import com.yjjoker.learningagent.harness.error.HarnessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("阿里云 LLM 客户端测试")
class AliyunLlmClientTest {

    // 测试使用 JDK 自带的本地 HTTP 服务器模拟阿里云接口，不会连接外网或消耗模型额度。
    private HttpServer server;

    @AfterEach
    void stopServer() {
        // 无论测试成功还是失败，都关闭服务器并释放随机端口，避免影响后续测试。
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("把普通模型消息解析成文本结果")
    void shouldParseTextResponse() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        startServer(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "事务是一组不可分割的操作。"
                      }
                    }
                  ]
                }
                """,
                authorization,
                requestBody
        );

        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new FakeTool("find_all_users", "查找到目前所有用户的用户名")
        ));
        AliyunLlmClient client = createClient(toolRegistry);

        LlmResponse response = client.generate(List.of(
                LlmMessage.system("你是 LearningAgent 学习助手"),
                LlmMessage.user("什么是数据库事务？")
        ));

        // assertInstanceOf 先验证结构化类型，再返回已经转换好的对象供后面的字段断言使用。
        TextLlmResponse textResponse = assertInstanceOf(TextLlmResponse.class, response);
        assertEquals("事务是一组不可分割的操作。", textResponse.content());
        assertEquals("Bearer test-api-key", authorization.get());

        // System Prompt 和用户消息都应进入 messages，工具定义仍然通过 tools 单独发送。
        assertTrue(requestBody.get().contains("\"model\":\"qwen-plus\""));
        assertTrue(requestBody.get().contains("\"role\":\"system\""));
        assertTrue(requestBody.get().contains("你是 LearningAgent 学习助手"));
        assertTrue(requestBody.get().contains("\"role\":\"user\""));
        assertTrue(requestBody.get().contains("什么是数据库事务？"));
        assertTrue(requestBody.get().contains("\"tools\""));
        assertTrue(requestBody.get().contains("\"name\":\"find_all_users\""));
        assertTrue(requestBody.get().contains("\"additionalProperties\":false"));
    }

    @Test
    @DisplayName("把模型 tool_calls 解析成工具调用结果")
    void shouldParseToolCallResponse() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();

        startServer(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_123",
                            "type": "function",
                            "function": {
                              "name": "find_all_users",
                              "arguments": "{}"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """,
                new AtomicReference<>(),
                requestBody
        );

        AliyunLlmClient client = createClient(new ToolRegistry(List.of(
                new FakeTool("find_all_users", "查找到目前所有用户的用户名")
        )));

        LlmResponse response = client.generate(List.of(LlmMessage.user("系统中有哪些用户？")));

        ToolCallLlmResponse toolResponse = assertInstanceOf(ToolCallLlmResponse.class, response);
        assertEquals(1, toolResponse.toolCalls().size());
        assertEquals("call_123", toolResponse.toolCalls().getFirst().id());
        assertEquals("find_all_users", toolResponse.toolCalls().getFirst().name());
        assertEquals("{}", toolResponse.toolCalls().getFirst().arguments());
    }

    @Test
    @DisplayName("第二次请求会发送 assistant 工具请求和对应的 tool 结果")
    void shouldSerializeToolConversation() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();

        // 本测试只关心请求序列化，所以模拟服务器最终返回一段普通文本即可。
        startServer(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "目前有两位用户。"
                      }
                    }
                  ]
                }
                """,
                new AtomicReference<>(),
                requestBody
        );

        AliyunLlmClient client = createClient(new ToolRegistry(List.of(
                new FakeTool("find_all_users", "查找到目前所有用户的用户名")
        )));
        ToolCall toolCall = new ToolCall("call_123", "find_all_users", "{}");

        // 这就是 Harness 在执行工具后交给客户端的完整三条消息。
        client.generate(List.of(
                LlmMessage.user("系统中有哪些用户？"),
                LlmMessage.assistantToolCalls(List.of(toolCall)),
                LlmMessage.toolResult("call_123", "张三, 李四")
        ));

        // assistant 消息保存模型原始工具请求，tool 消息用相同 id 回传执行结果。
        assertTrue(requestBody.get().contains("\"role\":\"assistant\""));
        assertTrue(requestBody.get().contains("\"tool_calls\""));
        assertTrue(requestBody.get().contains("\"id\":\"call_123\""));
        assertTrue(requestBody.get().contains("\"arguments\":\"{}\""));
        assertTrue(requestBody.get().contains("\"role\":\"tool\""));
        assertTrue(requestBody.get().contains("\"tool_call_id\":\"call_123\""));
        assertTrue(requestBody.get().contains("张三, 李四"));
    }

    @Test
    @DisplayName("429 和 5xx 错误转换为可重试的统一 LLM 错误")
    void shouldClassifyRetryableHttpErrors() throws IOException {
        startErrorServer(429);

        AliyunLlmClient rateLimitClient = createClient(new ToolRegistry(List.of()));
        HarnessException rateLimitException = org.junit.jupiter.api.Assertions.assertThrows(
                HarnessException.class,
                () -> rateLimitClient.generate(List.of(LlmMessage.user("测试限流")))
        );
        assertEquals("LLM_RATE_LIMITED", rateLimitException.getErrorCode());
        assertTrue(rateLimitException.getError().isRetryable());

        server.stop(0);
        startErrorServer(503);
        AliyunLlmClient serverErrorClient = createClient(new ToolRegistry(List.of()));

        HarnessException serverException = org.junit.jupiter.api.Assertions.assertThrows(
                HarnessException.class,
                () -> serverErrorClient.generate(List.of(LlmMessage.user("测试服务端异常")))
        );
        assertEquals("LLM_SERVER_ERROR", serverException.getErrorCode());
        assertTrue(serverException.getError().isRetryable());
    }

    @Test
    @DisplayName("鉴权和请求参数错误不可自动重试")
    void shouldClassifyNonRetryableHttpErrors() throws IOException {
        startErrorServer(401);

        AliyunLlmClient authClient = createClient(new ToolRegistry(List.of()));
        HarnessException authException = org.junit.jupiter.api.Assertions.assertThrows(
                HarnessException.class,
                () -> authClient.generate(List.of(LlmMessage.user("测试鉴权错误")))
        );
        assertEquals("LLM_AUTHENTICATION_FAILED", authException.getErrorCode());
        assertTrue(!authException.getError().isRetryable());

        server.stop(0);
        startErrorServer(400);
        AliyunLlmClient requestErrorClient = createClient(new ToolRegistry(List.of()));

        HarnessException requestException = org.junit.jupiter.api.Assertions.assertThrows(
                HarnessException.class,
                () -> requestErrorClient.generate(List.of(LlmMessage.user("测试请求错误")))
        );
        assertEquals("LLM_INVALID_REQUEST", requestException.getErrorCode());
        assertTrue(!requestException.getError().isRetryable());
    }

    // 启动本地模拟接口并记录客户端实际发送的鉴权请求头和 JSON 请求体。
    private void startServer(
            String responseJson,
            AtomicReference<String> authorization,
            AtomicReference<String> requestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendResponse(exchange, responseJson);
        });
        server.start();
    }

    // 启动只返回指定状态码的本地服务，验证客户端不会依赖异常文本判断错误类型。
    private void startErrorServer(int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        server.start();
    }

    // 构造客户端时把 baseUrl 指向本地模拟服务器，其他配置保持与真实运行时相同的使用方式。
    private AliyunLlmClient createClient(ToolRegistry toolRegistry) {
        AliyunLlmProperties properties = new AliyunLlmProperties();
        properties.setApiKey("test-api-key");
        properties.setModel("qwen-plus");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
        properties.setConnectTimeoutSeconds(2);
        properties.setReadTimeoutSeconds(2);
        return new AliyunLlmClient(properties, toolRegistry);
    }

    // 将每个测试准备好的 JSON 原样返回，让测试可以分别模拟文本回复和工具调用回复。
    private void sendResponse(HttpExchange exchange, String responseJson) throws IOException {
        byte[] responseBody = responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBody.length);
        exchange.getResponseBody().write(responseBody);
        exchange.close();
    }

    // 测试工具只提供元数据；如果客户端意外执行工具，就立即抛出异常证明职责发生了混淆。
    private static class FakeTool implements Tool {

        private final String name;
        private final String description;

        private FakeTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public ToolExecutionResult execute(String input) {
            throw new IllegalStateException("AliyunLlmClient 不应该执行工具");
        }
    }
}
