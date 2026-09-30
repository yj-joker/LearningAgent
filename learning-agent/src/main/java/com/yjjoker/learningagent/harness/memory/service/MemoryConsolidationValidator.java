package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationFormatException;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationPlan;
import com.yjjoker.learningagent.harness.memory.model.MemoryConsolidationSnapshot;
import com.yjjoker.learningagent.harness.memory.model.MemoryMergeCandidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 提案生成和保存入口共用校验，防止重复目标或未知引用进入数据库操作。
public final class MemoryConsolidationValidator {
    // 一次反馈多个错误，但限制反馈长度，避免修复提示挤占原始记忆的预算。
    private static final int MAX_ERRORS = 8;

    // 只提供静态校验方法，不创建对象。
    private MemoryConsolidationValidator() {
    }

    // 检查所有合并与冲突目标，全部通过后才允许写入。
    public static void validate(MemoryConsolidationSnapshot snapshot, MemoryConsolidationPlan plan) {
        if (plan == null || plan.getMerges() == null || plan.getConflicts() == null) {
            throw new MemoryConsolidationFormatException("merges 和 conflicts 必须是数组");
        }
        Map<String, String> used = new HashMap<>();
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < plan.getMerges().size(); index++) {
            MemoryMergeCandidate merge = plan.getMerges().get(index);
            String path = "merges[" + index + "]";
            if (merge == null) {
                addError(errors, path + "：合并组不能为空");
                continue;
            }
            // 保留项和待删除项必须都来自本次完整快照。
            validateRefs(snapshot, merge.getSourceRefs(), path + ".sourceRefs", used, errors);
            // keepRef 只指向本组保留项，不再放入 used，避免把合法指向当成重复分组。
            if (merge.getKeepRef() == null || merge.getSourceRefs() == null
                    || !merge.getSourceRefs().contains(merge.getKeepRef())) {
                addError(errors, path + ".keepRef：keepRef 必须属于 sourceRefs");
            }
            requireText(merge.getMemoryTopic(), 128, path + ".memoryTopic", errors);
            requireText(merge.getMemorySummary(), 1000, path + ".memorySummary", errors);
            requireText(merge.getMemoryContent(), 20000, path + ".memoryContent", errors);
        }
        // 同一个目标不能一边报告冲突，一边被另一组删除。
        for (int index = 0; index < plan.getConflicts().size(); index++) {
            validateRefs(snapshot, plan.getConflicts().get(index), "conflicts[" + index + "]", used, errors);
        }
        // 有一个错误就拒绝整份方案；收集错误只是为了让下一次修复更准确。
        if (!errors.isEmpty()) {
            throw new MemoryConsolidationFormatException(String.join("；\n", errors));
        }
    }

    // 检查每组至少有两个不同引用，而且整个方案不重复使用目标。
    private static void validateRefs(MemoryConsolidationSnapshot snapshot, List<String> refs, String path,
                                     Map<String, String> used, List<String> errors) {
        if (refs == null || refs.size() < 2) {
            addError(errors, path + "：每组至少需要两个记忆引用；独立记忆应省略，不要输出单元素组");
        }
        if (refs == null) {
            return;
        }
        // 单元素组虽然已不合法，仍检查它是否与其他组重复，避免下一次才发现第二个错误。
        for (int index = 0; index < refs.size(); index++) {
            String ref = refs.get(index);
            String location = path + "[" + index + "]";
            if (ref == null || snapshot.resolve(ref) == null) {
                // 不回显未知引用，防止把模型生成的无关文本带进日志和修复提示。
                addError(errors, location + "：只能使用本次提供的 memoryRef");
                continue;
            }
            String firstLocation = used.putIfAbsent(ref, location);
            if (firstLocation != null) {
                addError(errors, location + "：记忆引用不能重复或跨组合并；首次出现于 " + firstLocation);
            }
        }
    }

    // 拒绝空正文和过长文本，不截断模型输出后继续保存。
    private static void requireText(String text, int maxLength, String field, List<String> errors) {
        if (text == null || text.isBlank() || text.length() > maxLength) {
            addError(errors, field + " 必须为非空文本且不超过 " + maxLength + " 字符");
        }
    }

    // 仅收集后端生成的原因，最多八条；超过上限仍然拒绝整份方案。
    private static void addError(List<String> errors, String reason) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(reason);
        }
    }
}
