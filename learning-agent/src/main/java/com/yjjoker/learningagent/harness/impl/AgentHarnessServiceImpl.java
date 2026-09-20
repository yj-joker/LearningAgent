package com.yjjoker.learningagent.harness.impl;

import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.harness.AgentHarnessService;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.prompt.AgentSystemPrompt;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

// Harness 的业务实现类，负责安排模型调用流程，而不是负责拼接厂商 HTTP 请求。
// 完整流程是“请求模型 -> 判断结果类型 -> 必要时执行工具 -> 回传工具结果 -> 再请求模型”。
@Slf4j
@Service
public class AgentHarnessServiceImpl implements AgentHarnessService {

    // LLM 接口中的 tool content 是字符串，因此需要把 ToolExecutionResult 转成 JSON 文本。
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    // 最多允许模型连续进行五轮工具调用，防止模型反复调用工具形成死循环并持续消耗费用。
    // 这里限制的是工具轮数；模型在第五轮工具执行后仍有一次机会生成最终文本。
    private static final int MAX_TOOL_ROUNDS = 5;

    // 字段类型使用 LlmClient 接口，而不是 AliyunLlmClient 具体类。
    // 这样 Harness 不会和阿里云绑定，测试时也能传入不访问网络的假客户端。
    private final LlmClient llmClient;

    // Harness 通过注册表按照模型返回的工具名称找到真正的 Java 工具对象。
    private final ToolRegistry toolRegistry;

    // Spring 发现当前类只有一个构造方法，会把 LlmClient 和 ToolRegistry 两个 Bean 都传进来。
    // LlmClient 负责与模型通信，ToolRegistry 负责查找工具，两者职责不能混在一起。
    public AgentHarnessServiceImpl(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String run(String userMessage) {
        // ArrayList 允许在每轮结束后追加模型工具请求和工具结果，逐步构造完整对话历史。
        List<LlmMessage> messages = new ArrayList<>();

        // System Prompt 必须位于第一条，它告诉模型整个 Agent Loop 应遵守什么规则。
        // messages 会在循环中一直复用，所以后续工具请求也会继续携带这条系统消息。
        messages.add(LlmMessage.system(AgentSystemPrompt.CONTENT));
        messages.add(LlmMessage.user(userMessage));

        int completedToolRounds = 0;
        while (true) {
            // generate 只生成“模型下一步”，它可能是最终文本，也可能是需要 Java 执行的工具调用。
            // 得到模型的回复
            LlmResponse response = llmClient.generate(messages);

            //是最终结果？
            if (response instanceof TextLlmResponse textResponse) {
                return textResponse.content();
            }

            // 是工具调用？
            if (response instanceof ToolCallLlmResponse toolCallResponse) {
                if (completedToolRounds >= MAX_TOOL_ROUNDS) {
                    throw new IllegalStateException("Harness 超过最多 5 轮工具调用，已停止继续执行");
                }

                List<ToolCall> toolCalls = toolCallResponse.toolCalls();

                // 先把 assistant 的原始工具请求放回历史，保留每个调用的 id、名称和参数。
                // 将工具调用放回历史，让LLM知道自己调用了那些工具，
                // 如果只放工具结果，模型在下一轮无法还原自己为什么收到这些结果。
                messages.add(LlmMessage.assistantToolCalls(toolCalls));

                // 一次响应可能要求调用多个工具，所以必须逐个执行并分别添加结果消息。
                for (ToolCall toolCall : toolCalls) {
                    // 根据工具名称找到 Java 工具对象
                    Tool tool = toolRegistry.getRequiredTool(toolCall.name());

                    // 可修正的参数错误会作为失败结果正常返回；数据库故障等系统异常会在这里终止请求。
                    ToolExecutionResult toolResult = executeTool(tool, toolCall.arguments());
                    String toolResultJson = serializeToolResult(toolResult);

                    // 使用原始调用 id 建立一一对应关系，不能使用工具名称代替 id。
                    // 同一个工具在一轮中可能被调用两次，而这两次调用会拥有不同的 id。
                    messages.add(LlmMessage.toolResult(toolCall.id(), toolResultJson));
                }

                completedToolRounds++;
                // 工具执行后不能直接把结果返回用户，因为工具只提供原始数据。
                // 回到循环顶部，把结果交给模型，让模型结合用户问题组织最终自然语言答案。
                continue;
            }

            // LlmResponse 是 sealed 类型，正常情况下只有上面两种实现；这个检查用于防御空返回值。
            throw new IllegalStateException("LLM 客户端返回了无法识别的结果");
        }
    }

    private ToolExecutionResult executeTool(Tool tool, String arguments) {
        try {
            ToolExecutionResult result = tool.execute(arguments);
            if (result == null) {
                throw new IllegalStateException("工具返回结果不能为空");
            }
            return result;
        } catch (RuntimeException exception) {
            // 完整异常只写入服务端日志，避免把数据库或代码内部信息暴露给 LLM。
            log.error("工具执行发生系统异常，toolName={}", tool.name(), exception);
            throw new LearningAgentServiceException("工具执行失败，请稍后重试", exception);
        }
    }

    private String serializeToolResult(ToolExecutionResult result) {
        try {
            // 结构化 JSON 让模型能明确读取 success、errorCode、message 和 retryable。
            return JSON_MAPPER.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new LearningAgentServiceException("工具结果序列化失败", exception);
        }
    }
}
