package com.yjjoker.learningagent.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yjjoker.learningagent.config.AliyunLlmProperties;
import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorCode;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

// LlmClient 的阿里云实现，负责在项目内部消息与阿里云 JSON 格式之间进行转换。
// “OpenAI 兼容”表示请求和响应字段采用相近格式，不表示这里实际调用的是 OpenAI 服务器。
@Component
public class AliyunLlmClient implements LlmClient {

    private final AliyunLlmProperties properties;

    // 注册表保存了 Spring 启动时收集到的所有 Tool Bean。
    // 客户端不会执行这些工具，只读取名称、说明和参数结构并发送给 LLM。
    private final ToolRegistry toolRegistry;

    // RestClient 是 Spring 提供的同步 HTTP 客户端，负责真正建立网络连接并发送请求。
    // 本项目已经引入 Spring WebMVC，所以这里不需要再引入 Spring AI 或其他 HTTP 依赖。
    private final RestClient restClient;

    // Spring 创建当前组件时，会同时注入配置对象和已经完成 Java 工具注册的 ToolRegistry。
    public AliyunLlmClient(AliyunLlmProperties properties, ToolRegistry toolRegistry) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;

        // RequestFactory 控制底层 HTTP 连接行为。
        // 如果不设置超时，远端服务或网络异常时，请求线程可能长时间无法释放。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();

        // 连接超时限制 TCP/TLS 连接的建立时间，此时请求数据通常还没有发送完成。
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));

        // 读取超时限制连接成功后等待模型响应的时间，模型生成文字通常需要几秒甚至更久。
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()));

        // 创建只供当前模型客户端使用的 RestClient，并让它采用上面配置好的超时规则。
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    // 带工具的调用，用于通用语言模型。
    @Override
    public LlmResponse generate(List<LlmMessage> messages) {
        return generateInternal(messages, buildToolDefinitions());
    }

    // 不带工具的调用，不能让模型看到业务工具，否则摘要过程可能再次进入 Agent Loop。
    @Override
    public LlmResponse generateWithoutTools(List<LlmMessage> messages) {
        return generateInternal(messages, List.of());
    }

    private LlmResponse generateInternal(List<LlmMessage> messages, List<ChatTool> tools) {
        // 每一次请求都需要上下文。第一次只有 user 消息，工具执行后的请求还会包含 assistant 和 tool 消息。
        if (messages == null || messages.isEmpty()) {
            throw llmError(
                    HarnessErrorCode.INVALID_ARGUMENT,
                    "发送给大语言模型的消息列表不能为空",
                    false
            );
        }

        // 构造符合 Chat Completions 格式的请求体。
        // LlmMessage 是项目内部格式，需要先逐条转换为兼容接口要求的字段名称和嵌套结构。
        ChatRequest request = new ChatRequest(
                properties.getModel(),
                messages.stream().map(this::toChatMessage).toList(),
                // tools 只对当前模型请求生效，下一次请求仍然需要重新发送。
                tools
        );

        ChatResponse response;
        try {
            // 向配置中的聊天接口发送 POST 请求，并将 ChatRequest 自动序列化成 JSON。
            response = restClient.post()
                    .uri(properties.getBaseUrl())
                    // Content-Type 描述发送给服务器的请求体是 JSON。
                    .contentType(MediaType.APPLICATION_JSON)
                    // Accept 表示客户端希望服务器也返回 JSON。
                    .accept(MediaType.APPLICATION_JSON)
                    // Bearer 是兼容接口约定的鉴权格式，API Key 放在请求头而不是请求体中。
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(request)
                    // retrieve() 真正发出请求；非成功 HTTP 状态也会被转换成 RestClientException。
                    .retrieve()
                    // Spring 根据 ChatResponse 的字段和 getter/setter，把响应 JSON 反序列化成 Java 对象。
                    .body(ChatResponse.class);
        } catch (RestClientResponseException exception) {
            // HTTP 状态码比异常文本稳定，先把限流、服务端和客户端请求错误分开。
            int statusCode = exception.getStatusCode().value();
            throw httpError(statusCode, exception);
        } catch (ResourceAccessException exception) {
            // 连接或读取阶段失败没有 HTTP 响应，需要从原因链识别是否超时。
            if (containsTimeout(exception)) {
                throw llmError(
                        HarnessErrorCode.LLM_TIMEOUT,
                        "调用大语言模型超时，请稍后重试",
                        true,
                        exception
                );
            }
            throw llmError(
                    HarnessErrorCode.LLM_REQUEST_FAILED,
                    "调用大语言模型失败，请稍后重试",
                    true,
                    exception
            );
        } catch (RestClientException exception) {
            // 响应体解析阶段的读取超时可能被 Spring 包装成普通 RestClientException。
            // 仍需沿着 cause 链识别超时，不能因为包装类型变化而丢失错误语义。
            if (containsTimeout(exception)) {
                throw llmError(
                        HarnessErrorCode.LLM_TIMEOUT,
                        "调用大语言模型超时，请稍后重试",
                        true,
                        exception
                );
            }
            // 其他客户端异常仍归入通用请求失败，保留后续重试的可能性。
            throw llmError(
                    HarnessErrorCode.LLM_REQUEST_FAILED,
                    "调用大语言模型失败，请稍后重试",
                    true,
                    exception
            );
        }

        // HTTP 请求成功不等于本轮一定是文本回答，还可能是模型生成的工具调用要求。
        return extractResponse(response);
    }

    // 把厂商无关的内部消息转换成 Chat Completions 消息。
    // 这样 Harness 不需要认识 tool_calls、tool_call_id 等阿里云兼容接口字段。
    private ChatMessage toChatMessage(LlmMessage message) {
        if (message == null || message.getRole() == null || message.getRole().isBlank()) {
            throw llmError(
                    HarnessErrorCode.INVALID_ARGUMENT,
                    "消息角色不能为空",
                    false
            );
        }

        List<ChatToolCall> chatToolCalls = null;
        if (!message.getToolCalls().isEmpty()) {
            chatToolCalls = message.getToolCalls().stream()
                    .map(toolCall -> new ChatToolCall(
                            toolCall.id(),
                            "function",
                            new CalledFunction(toolCall.name(), toolCall.arguments())
                    ))
                    .toList();
        }

        return new ChatMessage(
                message.getRole(),
                message.getContent(),
                chatToolCalls,
                message.getToolCallId()
        );
    }

    // 将项目内部的 Tool 接口转换成阿里云 OpenAI 兼容接口所需的 tools 数组。
    // Chat Completions 格式要求外层 type=function，具体函数定义放在 function 字段中。
    private List<ChatTool> buildToolDefinitions() {
        return toolRegistry.getAllTools().stream()
                .map(tool -> new ChatTool(
                        "function",
                        new ChatFunction(
                                tool.name(),
                                tool.description(),
                                tool.parametersSchema()
                        )
                ))
                .toList();
    }

    // 从完整响应中提取第一条候选结果，并转换成 Harness 能理解的结构化类型。
    // 判断顺序很重要：tool_calls 非空时优先处理工具调用，即使厂商同时返回了辅助性的 content。
    private LlmResponse extractResponse(ChatResponse response) {
        // choices 为空说明服务器没有返回任何候选答案，不能继续向上层返回一个含义不明的 null。
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw llmError(
                    HarnessErrorCode.LLM_INVALID_RESPONSE,
                    "大语言模型没有返回可用结果",
                    false
            );
        }

        // 当前只使用第一个候选结果。
        ChatMessage message = response.getChoices().getFirst().getMessage();
        if (message == null) {
            throw llmError(
                    HarnessErrorCode.LLM_INVALID_RESPONSE,
                    "大语言模型返回的消息为空",
                    false
            );
        }

        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            List<ToolCall> toolCalls = message.getToolCalls().stream()
                    .map(this::toToolCall)
                    .toList();
            return new ToolCallLlmResponse(toolCalls);
        }

        if (message.getContent() != null && !message.getContent().isBlank()) {
            return new TextLlmResponse(message.getContent());
        }

        // 没有文本也没有工具调用时，Harness 不知道下一步应该做什么，因此把它视为响应格式异常。
        throw llmError(
                HarnessErrorCode.LLM_INVALID_RESPONSE,
                "大语言模型既没有返回文本，也没有返回工具调用",
                false
        );
    }

    // 把厂商响应中的工具调用结构转换成项目内部结构，并在边界处检查执行所需的关键字段。
    private ToolCall toToolCall(ChatToolCall chatToolCall) {
        if (chatToolCall == null
                || chatToolCall.getId() == null
                || chatToolCall.getId().isBlank()
                || chatToolCall.getFunction() == null
                || chatToolCall.getFunction().getName() == null
                || chatToolCall.getFunction().getName().isBlank()) {
            throw llmError(
                    HarnessErrorCode.LLM_INVALID_RESPONSE,
                    "大语言模型返回了不完整的工具调用",
                    false
            );
        }

        // 无参数工具通常返回字符串形式的空 JSON 对象 {}。
        // 若兼容服务省略 arguments，则在这里补成 {}，让 Tool.execute 始终收到可解析的 JSON 文本。
        String arguments = chatToolCall.getFunction().getArguments();
        if (arguments == null || arguments.isBlank()) {
            arguments = "{}";
        }
        return new ToolCall(chatToolCall.getId(), chatToolCall.getFunction().getName(), arguments);
    }

    // 统一创建 LLM 边界错误；这里只记录错误类型，不把请求正文或密钥写入日志。
    private HarnessException llmError(HarnessErrorCode errorCode,
                                      String message,
                                      boolean retryable) {
        return new HarnessException(HarnessError.of(
                errorCode,
                message,
                retryable,
                HarnessErrorSource.LLM
        ));
    }

    private HarnessException llmError(HarnessErrorCode errorCode,
                                      String message,
                                      boolean retryable,
                                      Throwable cause) {
        return new HarnessException(HarnessError.of(
                errorCode,
                message,
                retryable,
                HarnessErrorSource.LLM
        ), cause);
    }

    // 按 HTTP 状态码转换稳定错误；响应正文不写入日志也不返回，避免泄露厂商内部细节。
    private HarnessException httpError(int statusCode, RestClientResponseException cause) {
        if (statusCode == 401 || statusCode == 403) {
            return llmError(
                    HarnessErrorCode.LLM_AUTHENTICATION_FAILED,
                    "大语言模型鉴权失败，请检查 API Key 配置",
                    false,
                    cause
            );
        }
        if (statusCode == 429) {
            return llmError(
                    HarnessErrorCode.LLM_RATE_LIMITED,
                    "大语言模型请求频率受限，请稍后重试",
                    true,
                    cause
            );
        }
        if (statusCode >= 500 && statusCode <= 599) {
            return llmError(
                    HarnessErrorCode.LLM_SERVER_ERROR,
                    "大语言模型服务暂时不可用，请稍后重试",
                    true,
                    cause
            );
        }
        if (statusCode >= 400 && statusCode <= 499) {
            return llmError(
                    HarnessErrorCode.LLM_INVALID_REQUEST,
                    "大语言模型请求参数无效，请检查模型配置和消息格式",
                    false,
                    cause
            );
        }
        return llmError(
                HarnessErrorCode.LLM_REQUEST_FAILED,
                "调用大语言模型失败，请稍后重试",
                true,
                cause
        );
    }

    // 不依赖具体 HTTP 客户端实现，通过原因链兼容连接超时和读取超时。
    private boolean containsTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof IOException
                    && current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // 描述发送给兼容接口的 JSON：模型名称、消息列表和本轮允许使用的工具列表。
    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static class ChatRequest {

        private final String model;
        private final List<ChatMessage> messages;
        private final List<ChatTool> tools;
    }

    // NON_NULL 会省略当前消息不需要的字段，例如 user 消息不会发送 tool_calls 和 tool_call_id。
    // 下划线字段使用 JsonProperty 映射，因为 Java 通常使用驼峰命名，而接口 JSON 使用蛇形命名。
    // Jackson 解析响应时需要无参构造方法和 setter，Lombok 会在编译阶段生成这些样板代码。
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class ChatMessage {

        private String role;
        private String content;

        @JsonProperty("tool_calls")
        private List<ChatToolCall> toolCalls;

        @JsonProperty("tool_call_id")
        private String toolCallId;
    }

    // 描述响应中当前关心的 choices 字段，未声明的其他 JSON 字段会由反序列化过程忽略。
    @Getter
    @Setter
    @NoArgsConstructor
    private static class ChatResponse {
        private List<Choice> choices;
    }

    // 每个候选结果中包含一条模型生成的 message。
    @Getter
    @Setter
    @NoArgsConstructor
    private static class Choice {

        private ChatMessage message;
    }

    // 工具调用中的 id 是关联后续结果的唯一标识，type 在兼容接口中固定为 function。
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static class ChatToolCall {

        private String id;
        private String type;
        private CalledFunction function;
    }

    // arguments 是 JSON 字符串而不是 Java Map，例如无参数工具收到的是字符串 "{}"。
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static class CalledFunction {

        private String name;
        private String arguments;
    }

    // Chat Completions 使用这一层包装工具类型和具体函数定义。
    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static class ChatTool {

        private final String type;
        private final ChatFunction function;
    }

    // 这三个字段分别告诉模型：工具叫什么、适合何时使用、输入参数应该如何组织。
    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private static class ChatFunction {

        private final String name;
        private final String description;
        private final Map<String, Object> parameters;
    }
}
