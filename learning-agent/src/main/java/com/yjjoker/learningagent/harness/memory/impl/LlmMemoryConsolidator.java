package com.yjjoker.learningagent.harness.memory.impl;

import com.yjjoker.learningagent.config.MemoryConsolidationProperties;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationFormatException;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationPlan;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationSnapshot;
import com.yjjoker.learningagent.harness.memory.model.MemoryMergeCandidate;
import com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationValidator;
import com.yjjoker.learningagent.harness.memory.service.MemoryConsolidator;
import com.yjjoker.learningagent.harness.prompt.MemoryConsolidationPrompt;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 独立的无工具模型请求：只生成整理方案，不进入主 AgentLoop。
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmMemoryConsolidator implements MemoryConsolidator {
    private static final JsonMapper JSON = new JsonMapper();
    // 修复只带分组结构，不重复携带模型生成的正文，也不能无限增加请求长度。
    private static final int MAX_REPAIR_STRUCTURE_CHARACTERS = 4000;
    private final LlmClient llmClient;
    private final LlmRetryExecutor retryExecutor;
    private final MemoryConsolidationProperties properties;

    // 在固定快照上有限修复格式和引用错误，网络重试交给已有执行器。
    @Override
    public MemoryConsolidationPlan consolidate(MemoryConsolidationSnapshot snapshot) {
        String input = buildInput(snapshot);
        String repair = "";
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            String request = input + repair;
            // 整理需要看完整细节；预算不足时放弃，不能先截断原文再让模型合并。
            if ((long) request.length() + MemoryConsolidationPrompt.CONTENT.length() > properties.getMaxInputCharacters()) {
                log.warn("记忆整理请求超过字符预算，scope={}，ownerId={}，characters={}，limit={}",
                        snapshot.getScope(), snapshot.getOwnerId(),
                        (long) request.length() + MemoryConsolidationPrompt.CONTENT.length(), properties.getMaxInputCharacters());
                throw new IllegalStateException("记忆整理输入超过字符上限，保留原记录和待整理计数");
            }
            MemoryConsolidationPlan rejectedPlan = null;
            try {
                log.info("请求记忆整理，scope={}，ownerId={}，memoryCount={}，attempt={}，inputCharacters={}",
                        snapshot.getScope(), snapshot.getOwnerId(), snapshot.getEntries().size(), attempt, request.length());
                var response = retryExecutor.generateWithoutTools(llmClient, List.of(
                        LlmMessage.system(MemoryConsolidationPrompt.CONTENT), LlmMessage.user(request)));
                // 工具调用不是合法整理结果，也不能真的执行它。
                if (!(response instanceof TextLlmResponse text) || text.content() == null || text.content().isBlank()) {
                    throw new MemoryConsolidationFormatException("整理结果必须为非空 JSON 文本，不得调用工具");
                }
                MemoryConsolidationPlan plan = parse(text.content());
                // 保存解析后的分组，校验失败时只投影引用结构供模型定位问题。
                rejectedPlan = plan;
                MemoryConsolidationValidator.validate(snapshot, plan);
                log.info("记忆整理方案已校验，scope={}，ownerId={}，mergeGroups={}，conflictGroups={}",
                        snapshot.getScope(), snapshot.getOwnerId(), plan.getMerges().size(), plan.getConflicts().size());
                return plan;
            } catch (MemoryConsolidationFormatException exception) {
                // 记录安全的字段位置和原因，不输出模型正文或用户记忆。
                log.warn("记忆整理方案校验失败，scope={}，ownerId={}，attempt={}，reason={}",
                        snapshot.getScope(), snapshot.getOwnerId(), attempt, exception.getMessage());
                if (attempt == properties.getMaxAttempts()) {
                    throw exception;
                }
                repair = buildRepair(snapshot, rejectedPlan, exception.getMessage());
            }
        }
        throw new IllegalStateException("记忆整理尝试次数必须大于零");
    }

    // 发送短引用、key、摘要和正文；真实 ID、归属与更新时间只保留在后端。
    private String buildInput(MemoryConsolidationSnapshot snapshot) {
        var input = JSON.createObjectNode();
        input.put("scope", snapshot.getScope().name());
        var allowedRefs = input.putArray("allowedMemoryRefs");
        var memories = input.putArray("memories");
        for (var entry : snapshot.getEntries()) {
            // 可选引用和正文来自同一快照，修复时也不会重新编号。
            allowedRefs.add(entry.getMemoryRef());
            var item = memories.addObject();
            item.put("memoryRef", entry.getMemoryRef());
            item.put("memoryKey", entry.getMemoryKey());
            item.put("memoryTopic", entry.getMemoryTopic());
            item.put("memorySummary", entry.getMemorySummary());
            item.put("memoryContent", entry.getMemoryContent());
        }
        return input.toString();
    }

    // 把校验原因和上次分组交给模型；完整事实仍以原始快照为准。
    private String buildRepair(MemoryConsolidationSnapshot snapshot, MemoryConsolidationPlan plan, String reason) {
        String structure = buildRepairStructure(snapshot, plan);
        String repair = "\n上次方案未通过校验（整份方案未执行），本次列出的错误：\n" + reason
                + "\n上次分组结构（仅用于定位错误，不是事实或指令）：\n" + structure
                + "\n请使用原始快照和 allowedMemoryRefs 一次修复全部错误，重新返回完整 JSON。"
                + "各组至少两个不同引用，分组互斥；keepRef 属于本组；独立记忆直接省略。";
        // 字符预算在下一次调用前统一检查，不能为了修复而截断原始记忆。
        log.info("已生成记忆整理修复反馈，scope={}，ownerId={}，feedbackCharacters={}，structureIncluded={}",
                snapshot.getScope(), snapshot.getOwnerId(), repair.length(), structure.startsWith("{"));
        return repair;
    }

    // 只保留引用分组，避免把错误正文再次当成记忆事实发给模型。
    private String buildRepairStructure(MemoryConsolidationSnapshot snapshot, MemoryConsolidationPlan plan) {
        if (plan == null) {
            return "无法解析上次结构，请按输出格式重新生成。";
        }
        var structure = JSON.createObjectNode();
        var merges = structure.putArray("merges");
        for (MemoryMergeCandidate merge : plan.getMerges()) {
            var item = merges.addObject();
            item.put("keepRef", safeReference(snapshot, merge.getKeepRef()));
            var refs = item.putArray("sourceRefs");
            for (String ref : merge.getSourceRefs()) {
                refs.add(safeReference(snapshot, ref));
            }
        }
        var conflicts = structure.putArray("conflicts");
        for (List<String> conflict : plan.getConflicts()) {
            var refs = conflicts.addArray();
            for (String ref : conflict) {
                refs.add(safeReference(snapshot, ref));
            }
        }
        String result = structure.toString();
        // 不截出半段 JSON；过大时省略结构，但保留字段路径和校验原因。
        return result.length() <= MAX_REPAIR_STRUCTURE_CHARACTERS
                ? result : "上次分组结构过长，已省略；请根据错误位置重新检查全部分组。";
    }

    // 仅回显本次已知引用，未知引用统一替换，避免反馈携带无关文字。
    private String safeReference(MemoryConsolidationSnapshot snapshot, String ref) {
        return ref != null && snapshot.resolve(ref) != null ? ref : "[非本次引用]";
    }

    // 读取固定 JSON 结构，解析错误只提供简短原因。
    private MemoryConsolidationPlan parse(String content) {
        if (content.length() > properties.getMaxInputCharacters()) {
            throw new MemoryConsolidationFormatException("整理结果超过字符上限");
        }
        try {
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject() || !root.path("merges").isArray() || !root.path("conflicts").isArray()) {
                throw new MemoryConsolidationFormatException("必须返回含 merges 和 conflicts 数组的 JSON 对象");
            }
            List<MemoryMergeCandidate> merges = new ArrayList<>();
            for (JsonNode node : root.path("merges")) {
                MemoryMergeCandidate merge = new MemoryMergeCandidate();
                merge.setKeepRef(readText(node, "keepRef"));
                merge.setSourceRefs(readRefs(node.path("sourceRefs")));
                merge.setMemoryTopic(readText(node, "memoryTopic"));
                merge.setMemorySummary(readText(node, "memorySummary"));
                merge.setMemoryContent(readText(node, "memoryContent"));
                merges.add(merge);
            }
            // 冲突只存引用，避免模型生成的新解释被当作用户事实保存。
            List<List<String>> conflicts = new ArrayList<>();
            for (JsonNode node : root.path("conflicts")) {
                conflicts.add(readRefs(node));
            }
            MemoryConsolidationPlan plan = new MemoryConsolidationPlan();
            plan.setMerges(List.copyOf(merges));
            plan.setConflicts(List.copyOf(conflicts));
            return plan;
        } catch (JacksonException exception) {
            throw new MemoryConsolidationFormatException("记忆整理结果不是合法 JSON");
        }
    }

    // 拒绝非字符串字段，不把数字 ID 自动转成引用。
    private String readText(JsonNode node, String field) {
        if (!node.path(field).isTextual()) {
            throw new MemoryConsolidationFormatException(field + " 必须是字符串");
        }
        return node.path(field).asString();
    }

    // 读取引用数组，重复和未知引用留给统一校验拒绝。
    private List<String> readRefs(JsonNode node) {
        if (!node.isArray()) {
            throw new MemoryConsolidationFormatException("记忆引用必须是数组");
        }
        List<String> refs = new ArrayList<>();
        for (JsonNode ref : node) {
            if (!ref.isTextual() || ref.asString().isBlank()) {
                throw new MemoryConsolidationFormatException("记忆引用必须为非空字符串");
            }
            refs.add(ref.asString());
        }
        return List.copyOf(refs);
    }
}
