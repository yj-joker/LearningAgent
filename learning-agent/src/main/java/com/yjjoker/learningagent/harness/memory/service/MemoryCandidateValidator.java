package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.harness.memory.model.MemoryCandidate;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionContext;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionFormatException;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionTarget;
import com.yjjoker.learningagent.harness.memory.model.MemoryOperation;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

// 提取后和写入前共用校验，防止其他入口绕过目标检查。
public final class MemoryCandidateValidator {
    // 只提供校验方法，不创建对象。
    private MemoryCandidateValidator() {
    }

    // 检查本轮依据、操作字段和所有目标；任意候选错误都会拒绝整批。
    public static void validate(MemoryExtractionContext context, String userMessage,
                                List<MemoryCandidate> candidates) {
        Set<String> usedTargets = new HashSet<>();
        Set<String> createdKeys = new HashSet<>();
        for (MemoryCandidate candidate : candidates) {
            if (candidate == null || candidate.getScope() == null || candidate.getOperation() == null) {
                reject("候选必须包含 scope 和 operation");
            }
            // 必须引用本轮用户原话；这项检查不等于自动证明语义判断正确。
            requireText(candidate.getUserEvidence(), 10_000, "userEvidence");
            if (userMessage == null || !userMessage.contains(candidate.getUserEvidence())) {
                reject("userEvidence 必须是本轮用户消息中的连续原文，不能引用助手或旧索引");
            }
            List<String> refs = candidate.getTargetMemoryRefs();
            if (refs == null) {
                reject("targetMemoryRefs 必须是数组");
            }
            if (candidate.getOperation() == MemoryOperation.CREATE) {
                // 新增没有目标，但必须提供新的业务 key。
                if (!refs.isEmpty()) {
                    reject("CREATE 的 targetMemoryRefs 必须为空");
                }
                requireText(candidate.getMemoryKey(), 128, "memoryKey");
                if (!createdKeys.add(candidate.getScope() + ":" + candidate.getMemoryKey())) {
                    reject("同一批次不能重复新增同 scope 和 memoryKey");
                }
                if (context.getTargets().stream().anyMatch(target -> target.getScope() == candidate.getScope()
                        && Objects.equals(target.getMemoryKey(), candidate.getMemoryKey()))) {
                    reject("该 scope 中已有相同 memoryKey；应选择已有引用修改或不生成候选");
                }
            } else {
                // 修改和删除只能选择已提供的目标，不再按模型猜测的 key 查找。
                if (refs.isEmpty()) {
                    reject("UPDATE 和 DELETE 必须提供 targetMemoryRefs；目标不明确时返回空候选");
                }
                if (candidate.getMemoryKey() != null && !candidate.getMemoryKey().isBlank()) {
                    reject("UPDATE 和 DELETE 不填写 memoryKey，只使用 targetMemoryRefs");
                }
                for (String ref : refs) {
                    MemoryExtractionTarget target = context.resolve(ref);
                    if (target == null || target.getScope() != candidate.getScope()) {
                        reject("目标引用不存在于当前索引或与 scope 不一致");
                    }
                    Long ownerId = target.getScope() == MemoryScope.USER
                            ? context.getUserId() : context.getSessionId();
                    if (!Objects.equals(ownerId, target.getOwnerId())) {
                        reject("目标记忆不属于当前用户或会话");
                    }
                    // 一条记忆在同一批次只能处理一次，避免先更新又删除。
                    if (!usedTargets.add(ref)) {
                        reject("targetMemoryRefs 不能重复，也不能被多个候选重复操作");
                    }
                }
            }
            if (candidate.getOperation() != MemoryOperation.DELETE) {
                // 新增和修改必须提供完整的新内容，删除只需目标和依据。
                requireText(candidate.getMemoryTopic(), 128, "memoryTopic");
                requireText(candidate.getMemorySummary(), 1_000, "memorySummary");
                requireText(candidate.getMemoryContent(), 20_000, "memoryContent");
            }
        }
    }

    // 检查必填文本和长度，不把正文放进错误消息。
    private static void requireText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            reject(field + " 不能为空且不能超过 " + maximum + " 个字符");
        }
    }

    // 把可修复的候选错误交给现有的有限次数修复循环。
    private static void reject(String reason) {
        throw new MemoryExtractionFormatException(reason);
    }
}
