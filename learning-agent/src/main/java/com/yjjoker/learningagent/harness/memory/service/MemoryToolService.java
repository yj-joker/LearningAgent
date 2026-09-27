package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

// 主模型记忆工具的共同入口；不持有跨请求状态，不在模型请求期间占用数据库事务。
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryToolService {
    private static final JsonMapper JSON = new JsonMapper();
    private final MemoryReferenceRegistry references;
    private final StructuredMemoryService store;
    private final MemoryCandidatePersistenceService persistence;
    private final LearningSessionRepository sessions;

    // 根据固定操作生成最小参数结构，不允许模型提供用户 ID 或数据库主键。
    public Map<String, Object> schema(MemoryOperation operation) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("userEvidence", Map.of("type", "string", "description",
                "逐字复制本轮用户明确要求记住、修改或删除的完整指令，不引用历史、助手或工具内容"));
        if (operation == MemoryOperation.CREATE) {
            fields.put("scope", Map.of("type", "string", "enum", List.of("USER", "SESSION")));
            fields.put("memoryKey", textField("新事实的稳定 key，不填写已有 key"));
        } else {
            fields.put("targetMemoryRefs", Map.of("type", "array", "minItems", 1, "maxItems", 20,
                    "uniqueItems", true, "items", Map.of("type", "string"),
                    "description", "原样复制当前索引中同一范围内的目标引用；同义记录一起处理"));
        }
        if (operation != MemoryOperation.DELETE) {
            fields.put("memoryTopic", textField("记忆主题"));
            fields.put("memorySummary", textField("短索引摘要"));
            fields.put("memoryContent", textField("本轮用户确认的完整事实，不编造细节"));
        }
        return Map.of("type", "object", "properties", fields, "required", List.copyOf(fields.keySet()),
                "additionalProperties", false);
    }

    // 执行显式写入；正常返回的成功凭据只能在事务提交后交给主循环。
    public ToolExecutionResult write(MemoryOperation operation, String input) {
        try {
            MemoryExtractionContext context = requireContext();
            JsonNode node = parseObject(input);
            // Schema 只是告诉模型规则，这里才真正拒绝数据库 ID、用户 ID 等多余参数。
            @SuppressWarnings("unchecked")
            Set<String> allowed = ((Map<String, Object>) schema(operation).get("properties")).keySet();
            if (!allowed.containsAll(node.propertyNames())) {
                return failure("INVALID_ARGUMENT", "存在未允许的参数，请按工具结构重新填写", true);
            }
            MemoryCandidate candidate = parseCandidate(operation, node, context);
            requireExplicitRequest(operation, candidate.getUserEvidence(), references.currentUserMessage());
            // 共用后端校验，随后保存服务还会加锁检查版本、写入内容并增加整理计数。
            MemoryWriteReceipt receipt = persistence.persistToolCandidate(context,
                    references.currentUserMessage(), candidate);
            log.info("主循环记忆写入已提交，sessionId={}，operation={}，scope={}，targetCount={}",
                    context.getSessionId(), operation, receipt.getScope(), receipt.getMemoryIds().size());
            return committedResult(receipt);
        } catch (SecurityException exception) {
            // 身份或会话权限不满足时，重写工具参数不能解决问题。
            return failure("MEMORY_ACCESS_DENIED", "没有当前用户或会话的记忆操作权限", false);
        } catch (JacksonException | IllegalArgumentException | MemoryExtractionFormatException exception) {
            // 输入错误可以修正，不输出异常中可能包含的原始 JSON。
            return failure("INVALID_MEMORY_ARGUMENT", "参数或用户依据不合法，请使用本轮明确指令和当前记忆引用", true);
        } catch (ClientDataErrorException | NotFountException exception) {
            // 目标被并发修改或删除时，要求重新读索引，不用旧内容覆盖新内容。
            return failure("MEMORY_TARGET_CHANGED", "目标已变化、不可访问或同 key 已存在，请调用 list_memories 重新确认", true);
        } catch (DataAccessException exception) {
            // 网络中断等情况不能宣称一定未提交；不鼓励盲目重复写入。
            log.warn("记忆工具数据库操作失败，operation={}，exceptionType={}", operation, exception.getClass().getSimpleName());
            return failure("MEMORY_WRITE_FAILED", "未能确认记忆写入结果，请查询确认后再处理，不要盲目重试", false);
        }
    }

    // 只返回当前用户和当前会话的索引；完整正文继续使用 recall_memory 按需读取。
    public ToolExecutionResult list(String input) {
        try {
            requireContext();
            if (!parseObject(input).isEmpty()) {
                return failure("INVALID_ARGUMENT", "list_memories 不接收参数", true);
            }
            refreshIndex();
            var output = JSON.createObjectNode();
            var entries = output.putArray("memories");
            for (MemoryExtractionTarget target : references.toolContext().getTargets()) {
                var entry = entries.addObject();
                entry.put("memoryRef", target.getMemoryRef());
                entry.put("scope", target.getScope().name());
                entry.put("memoryKey", target.getMemoryKey());
                entry.put("memoryTopic", target.getMemoryTopic());
                entry.put("memorySummary", target.getMemorySummary());
            }
            log.info("主循环记忆索引查询完成，sessionId={}，memoryCount={}", references.currentSessionId(), entries.size());
            return ToolExecutionResult.success(output.toString());
        } catch (JacksonException | IllegalArgumentException exception) {
            return failure("INVALID_ARGUMENT", "参数必须是空 JSON 对象，且需要有效的当前请求", true);
        } catch (SecurityException exception) {
            return failure("MEMORY_ACCESS_DENIED", "无法访问当前用户或会话的记忆", false);
        }
    }

    // 当前登录用户必须与服务端请求映射一致；会话 ID 不从模型参数中读取。
    private MemoryExtractionContext requireContext() {
        if (BaseContext.getCurrentId() == null || references.currentUserId() == null || references.currentSessionId() == null) {
            throw new SecurityException("缺少登录用户或当前会话");
        }
        MemoryExtractionContext context = references.toolContext();
        if (!Objects.equals(BaseContext.getCurrentId(), context.getUserId())) {
            throw new SecurityException("记忆请求归属不一致");
        }
        var session = sessions.findSessionById(context.getSessionId())
                .orElseThrow(() -> new SecurityException("会话不存在"));
        if (!Objects.equals(session.getUserId(), context.getUserId()) || session.getStatus() != LearningSessionStatusEnum.ACTIVE) {
            throw new SecurityException("会话不可写");
        }
        return context;
    }

    // 转成现有候选对象，复用已有字段长度、证据、范围和重复目标检查。
    private MemoryCandidate parseCandidate(MemoryOperation operation, JsonNode node, MemoryExtractionContext context) {
        MemoryCandidate candidate = new MemoryCandidate();
        candidate.setOperation(operation);
        candidate.setUserEvidence(text(node, "userEvidence"));
        if (operation == MemoryOperation.CREATE) {
            candidate.setScope(MemoryScope.valueOf(text(node, "scope")));
            candidate.setMemoryKey(text(node, "memoryKey"));
            candidate.setTargetMemoryRefs(List.of());
        } else {
            JsonNode array = node.get("targetMemoryRefs");
            if (array == null || !array.isArray() || array.isEmpty() || array.size() > 20) {
                throw new IllegalArgumentException("目标数量应为 1 到 20");
            }
            List<String> refs = new ArrayList<>();
            for (JsonNode item : array) {
                if (!item.isTextual() || context.resolve(item.asString()) == null) {
                    throw new IllegalArgumentException("未知引用");
                }
                refs.add(item.asString());
            }
            candidate.setTargetMemoryRefs(List.copyOf(refs));
            // 范围由服务端引用推导，不能让模型把会话记忆冒充长期记忆。
            candidate.setScope(context.resolve(refs.getFirst()).getScope());
        }
        if (operation != MemoryOperation.DELETE) {
            candidate.setMemoryTopic(text(node, "memoryTopic"));
            candidate.setMemorySummary(text(node, "memorySummary"));
            candidate.setMemoryContent(text(node, "memoryContent"));
        }
        MemoryCandidateValidator.validate(context, references.currentUserMessage(), List.of(candidate));
        return candidate;
    }

    // 限定本轮原文中的明确命令，避免仅凭助手回答或旧历史触发写入。
    // 这是保守的文字规则，不是完整语义授权；TODO 后续接入用户审批，处理否定、引用等复杂表达。
    private void requireExplicitRequest(MemoryOperation operation, String evidence, String original) {
        if (original == null || !original.contains(evidence)) {
            throw new IllegalArgumentException("没有本轮用户依据");
        }
        String action = switch (operation) {
            case CREATE -> "(?:记住|记下|保存|添加|新增)";
            case UPDATE -> "(?:修改|更新|更改|将|把)";
            case DELETE -> "(?:忘记|删除|移除|清除)";
        };
        Pattern command = Pattern.compile("^(?:请(?:帮我)?|帮我|麻烦你|麻烦|现在|另外[，,]?)?\\s*" + action + ".+", Pattern.DOTALL);
        // 提问、否定和引用不作为这版工具的明确命令；不确定时宁可让用户重新说明。
        if (!command.matcher(evidence).matches() || evidence.matches("(?s).*[？?].*")
                || evidence.contains("不要") || evidence.contains("不用") || evidence.contains("别删除")) {
            throw new IllegalArgumentException("需要明确的记忆操作命令");
        }
        int position = original.indexOf(evidence);
        if (position > 0 && "。！？!?；;\n".indexOf(original.charAt(position - 1)) < 0) {
            throw new IllegalArgumentException("不能从引用或否定句中截取操作命令");
        }
    }

    // 数据库已提交后，刷新失败也不能把已完成的操作报告成失败。
    private ToolExecutionResult committedResult(MemoryWriteReceipt receipt) {
        var output = JSON.createObjectNode();
        output.put("operation", receipt.getOperation().name());
        output.put("scope", receipt.getScope().name());
        output.put("message", "记忆操作已完成");
        var refs = output.putArray("affectedMemoryRefs");
        var latest = output.putArray("affectedMemories");
        try {
            // 只更新本次处理的目标；不能悄悄刷新模型尚未看到的其他记忆版本。
            if (receipt.getOperation() == MemoryOperation.DELETE) {
                references.toolContext().currentRefsFor(receipt).forEach(references::remove);
            } else {
                for (Long id : receipt.getMemoryIds()) {
                    String ref = receipt.getScope() == MemoryScope.USER
                            ? references.registerUserMemory(store.recallUserMemory(receipt.getOwnerId(), id))
                            : references.registerSessionMemory(store.recallSessionMemory(receipt.getOwnerId(), id));
                    refs.add(ref);
                    // 更新版本的同时把新索引交给模型，后续写入仍以它实际看到的快照为依据。
                    MemoryExtractionTarget target = references.toolContext().resolve(ref);
                    var entry = latest.addObject();
                    entry.put("memoryRef", ref);
                    entry.put("memoryKey", target.getMemoryKey());
                    entry.put("memoryTopic", target.getMemoryTopic());
                    entry.put("memorySummary", target.getMemorySummary());
                }
            }
        } catch (RuntimeException exception) {
            log.warn("记忆已提交但索引刷新失败，sessionId={}，exceptionType={}",
                    references.currentSessionId(), exception.getClass().getSimpleName());
            output.put("refreshRequired", true);
            output.put("message", "记忆已处理，请调用 list_memories 刷新索引，不要重复写入");
        }
        return ToolExecutionResult.memoryWriteSuccess(output.toString(), receipt);
    }

    // 刷新使用带范围的数据库查询，编号由当前请求注册表保持稳定。
    private void refreshIndex() {
        references.refresh(new MemoryIndexSnapshot(store.loadUserMemoryIndex(references.currentUserId()),
                store.loadSessionMemoryIndex(references.currentSessionId())));
    }

    // 拒绝空对象以外的 JSON 类型；具体字段由各工具继续检查。
    private JsonNode parseObject(String input) {
        if (input == null || input.length() > 40_000) {
            throw new IllegalArgumentException("参数缺失或过长");
        }
        JsonNode node = JSON.readTree(input);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("参数必须是对象");
        }
        return node;
    }

    // 读取必填文本；不擅自改变用户依据的空格。
    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw new IllegalArgumentException("缺少必填文本");
        }
        return value.asString();
    }

    // 统一生成文本字段的工具说明。
    private Map<String, Object> textField(String description) {
        return Map.of("type", "string", "description", description);
    }

    // 日志只记录错误代码，不记录用户原文、工具参数或完整记忆正文。
    private ToolExecutionResult failure(String code, String message, boolean retryable) {
        log.warn("记忆工具未完成，sessionId={}，errorCode={}", references.currentSessionId(), code);
        return ToolExecutionResult.failure(code, message, retryable);
    }
}
