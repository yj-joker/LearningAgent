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

// 主模型记忆工具的共同入口；不持有跨请求状态，不在模型请求期间占用数据库事务。
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryToolService {
    private static final JsonMapper JSON = new JsonMapper();
    private final MemoryReferenceRegistry references;
    private final StructuredMemoryService store;
    private final LearningSessionRepository sessions;
    private final MemoryCandidatePersistenceService persistence;

    // 根据固定操作生成最小参数结构，不允许模型提供用户 ID 或数据库主键。
    public Map<String, Object> schema(MemoryOperation operation) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("userEvidence", Map.of("type", "string", "description",
                "逐字复制本轮用户完整消息，不截取片段，不引用历史、助手或工具内容；提交申请不代表获得执行授权"));
        if (operation == MemoryOperation.CREATE) {
            // 范围含义属于参数说明，不要求主提示词重复解释两个枚举。
            fields.put("scope", Map.of("type", "string", "enum", List.of("USER", "SESSION"),
                    "description", "USER 为跨会话长期记忆，SESSION 仅供当前会话使用；按用户要求选择，不扩大保存范围"));
            fields.put("memoryKey", textField("新事实的稳定 key，不填写已有 key"));
        } else {
            fields.put("targetMemoryRefs", Map.of("type", "array", "minItems", 1, "maxItems", 20,
                    "uniqueItems", true, "items", Map.of("type", "string"),
                    "description", "原样复制最新索引中同一范围内的完整 memoryRef；同义记录一起处理，不猜数据库 ID 或使用旧任务引用"));
        }
        if (operation != MemoryOperation.DELETE) {
            fields.put("memoryTopic", textField("记忆主题"));
            fields.put("memorySummary", textField("用于索引的简短事实摘要，不含临时引用或操作指令"));
            fields.put("memoryContent", textField("本轮用户确认的完整事实，不编造细节，不写入临时 memoryRef"));
        }
        return Map.of("type", "object", "properties", fields, "required", List.copyOf(fields.keySet()),
                "additionalProperties", false);
    }

    // 共用参数、归属和证据校验；预检不写入，批准后的执行才进入事务服务。
    private ToolExecutionResult process(MemoryOperation operation, String input, boolean execute) {
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
            requireCurrentUserEvidence(candidate.getUserEvidence(), references.currentUserMessage());
            if (!execute) {
                log.info("记忆工具预检完成，sessionId={}，operation={}，未执行写入", context.getSessionId(), operation);
                return ToolExecutionResult.success("记忆参数校验通过");
            }
            // 旧目标快照、实际写入和变更计数在同一事务核对，批准不能覆盖已发生的并发修改。
            MemoryWriteReceipt receipt = persistence.persistToolCandidate(context,
                    references.currentUserMessage(), candidate);
            log.info("已批准的记忆工具执行完成，sessionId={}，operation={}，affectedCount={}",
                    context.getSessionId(), operation, receipt.getMemoryIds().size());
            return ToolExecutionResult.memoryWriteSuccess("记忆操作已完成", receipt);
        } catch (SecurityException exception) {
            // 身份或会话权限不满足时，重写工具参数不能解决问题。
            return failure("MEMORY_ACCESS_DENIED", "没有当前用户或会话的记忆操作权限", false);
        } catch (JacksonException | IllegalArgumentException | MemoryExtractionFormatException exception) {
            // 输入错误可以修正，不输出异常中可能包含的原始 JSON。
            return failure("INVALID_MEMORY_ARGUMENT", "参数或用户依据不合法，请完整复制本轮用户消息并使用当前记忆引用", true);
        } catch (ClientDataErrorException | NotFountException exception) {
            // 目标被并发修改或删除时，要求重新读索引，不用旧内容覆盖新内容。
            return failure("MEMORY_TARGET_CHANGED", "目标已变化、不可访问或同 key 已存在，请调用 list_memories 重新确认", true);
        } catch (DataAccessException exception) {
            // 数据库错误不伪装成功；写入由事务服务回滚，日志只记录异常类型。
            log.warn("记忆工具数据库操作失败，operation={}，exceptionType={}", operation, exception.getClass().getSimpleName());
            return failure("MEMORY_LOOKUP_FAILED", "无法校验当前会话或记忆，请稍后重试", false);
        }
    }

    // 前置 Hook 调用此方法；通过只代表参数有效，不代表用户已经批准。
    public ToolExecutionResult validate(MemoryOperation operation, String input) {
        return process(operation, input, false);
    }

    // Harness 在审批通过且权限复查通过后调用 execute，工具才真正修改记忆。
    public ToolExecutionResult write(MemoryOperation operation, String input) {
        return process(operation, input, true);
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

    // 只证明依据来自本轮用户，不用正则猜测自然语言中的授权含义。
    private void requireCurrentUserEvidence(String evidence, String original) {
        // 完整保留否定、问句和引用的上下文，防止模型只截取一句“删除”。
        if (original == null || original.isBlank() || evidence == null || evidence.isBlank()
                || !original.strip().equals(evidence.strip())) {
            throw new IllegalArgumentException("userEvidence 必须完整复制本轮用户消息");
        }
        // “请记住我不要吃花生”可以提交申请；是否执行由用户对具体变更的审批决定。
        // 提示词仍要求明确指令，但安全边界是后端权限检查和用户审批，不是关键词匹配。
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
