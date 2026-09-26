package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.harness.memory.model.MemoryReference;
import com.yjjoker.learningagent.harness.memory.service.MemoryReferenceRegistry;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import com.yjjoker.learningagent.harness.memory.service.StructuredMemoryService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

// 根据当前 AgentLoop 的 memoryRef 召回一条记忆正文。
// 模型不接触数据库 memoryId，服务端会先解析引用，再按用户或会话范围查询。
@Component
@AllArgsConstructor
@Slf4j
public class RecallMemoryTool implements Tool {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    // 当前请求的引用表负责把模型可见的短引用映射为真实记忆目标。
    private final MemoryReferenceRegistry referenceRegistry;

    // 结构化记忆服务负责执行带范围校验的正文查询。
    private final StructuredMemoryService structuredMemoryService;

    @Override
    public String name() {
        return "recall_memory";
    }

    @Override
    public String description() {
        return "当记忆索引摘要不足以回答问题时，根据本次请求中的 memoryRef 读取对应记忆正文。";
    }

    @Override
    public boolean isContextScopedTool() {
        // memoryRef 只在当前 AgentLoop 有效，调用消息不能被未来请求重放。
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        // 只允许模型传入 memoryRef，禁止它自行构造数据库 ID 或其他范围参数。
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "memoryRef", Map.of(
                                "type", "string",
                                "description", "当前记忆索引中的引用，例如 memory_1；必须原样复制"
                        )
                ),
                "required", java.util.List.of("memoryRef"),
                "additionalProperties", false
        );
    }

    @Override
    public ToolExecutionResult execute(String input) {
        try {
            // 先解析 JSON，再读取 memoryRef，避免把整段参数误当成引用。
            JsonNode arguments = JSON_MAPPER.readTree(input);
            if (arguments == null || !arguments.isObject()) {
                return invalidArgument("参数必须是 JSON 对象");
            }

            JsonNode referenceNode = arguments.get("memoryRef");
            if (referenceNode == null || !referenceNode.isTextual()
                    || referenceNode.asString().isBlank()) {
                return invalidArgument("memoryRef 必须是非空字符串");
            }

            String memoryRef = referenceNode.asString();
            MemoryReference reference = referenceRegistry.resolve(memoryRef);
            if (reference == null) {
                log.warn("记忆引用不存在，memoryRef={}", memoryRef);
                return ToolExecutionResult.failure(
                        "INVALID_MEMORY_REFERENCE",
                        "找不到当前 AgentLoop 中的记忆引用，请使用索引中提供的 memoryRef",
                        true
                );
            }

            String content = recallContent(reference);
            log.info("记忆正文召回成功，memoryRef={}，scope={}，contentCharacters={}",
                    memoryRef, reference.getScope(), content.length());
            return ToolExecutionResult.success(content);
        } catch (NotFountException exception) {
            // 记忆在索引生成后被删除时，返回稳定业务错误，不暴露数据库异常。
            log.warn("记忆正文召回失败，记忆已不存在");
            return ToolExecutionResult.failure(
                    "MEMORY_NOT_FOUND",
                    "记忆不存在或已经被删除",
                    false
            );
        } catch (JacksonException | IllegalArgumentException exception) {
            return invalidArgument("参数不是有效的 JSON");
        }
    }

    // 根据引用范围调用对应的长期记忆或会话记忆召回方法。
    private String recallContent(MemoryReference reference) {
        if (reference.getScope() == MemoryScope.USER) {
            UserMemory memory = structuredMemoryService.recallUserMemory(
                    reference.getOwnerId(), reference.getMemoryId()
            );
            return formatContent(memory.getMemoryTopic(), memory.getMemoryKey(), memory.getMemoryContent());
        }

        SessionMemory memory = structuredMemoryService.recallSessionMemory(
                reference.getOwnerId(), reference.getMemoryId()
        );
        return formatContent(memory.getMemoryTopic(), memory.getMemoryKey(), memory.getMemoryContent());
    }

    // 给模型返回主题、key 和正文，帮助它理解正文属于哪类记忆。
    private String formatContent(String topic, String key, String content) {
        return "topic=" + topic + "\nkey=" + key + "\ncontent=" + content;
    }

    // 参数错误允许模型修正后重试，不把预期输入错误升级为系统异常。
    private ToolExecutionResult invalidArgument(String message) {
        return ToolExecutionResult.failure("INVALID_ARGUMENT", message, true);
    }
}
