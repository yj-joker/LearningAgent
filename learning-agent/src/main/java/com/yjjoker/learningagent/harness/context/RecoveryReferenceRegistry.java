package com.yjjoker.learningagent.harness.context;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// 保存本次 Agent Loop 中“短引用 -> 真实 toolCallId”的映射。
// 模型只需要复制 result_1，随机的 call_xxx 由 Harness 自己管理。
public class RecoveryReferenceRegistry {

    private final Map<String, String> toolCallIdByReference = new LinkedHashMap<>();
    private int nextReferenceNumber = 1;

    // 同一个 toolCallId 重复注册时复用原引用，避免一次结果出现多个别名。
    public String register(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId 不能为空");
        }
        for (Map.Entry<String, String> entry : toolCallIdByReference.entrySet()) {
            if (toolCallId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        // 引用被清理后不能按当前 Map 大小重新编号，否则可能覆盖仍在使用的旧引用。
        String reference = "result_" + nextReferenceNumber++;
        toolCallIdByReference.put(reference, toolCallId);
        return reference;
    }

    public String resolve(String reference) {
        return toolCallIdByReference.get(reference);
    }

    public List<String> references() {
        return List.copyOf(toolCallIdByReference.keySet());
    }

    // 摘要或裁剪后只保留当前上下文仍能看到的工具调用，令旧 recoveryRef 立即失效。
    public int retainToolCallIds(Collection<String> visibleToolCallIds) {
        int before = toolCallIdByReference.size();
        toolCallIdByReference.entrySet().removeIf(
                entry -> !visibleToolCallIds.contains(entry.getValue())
        );
        return before - toolCallIdByReference.size();
    }
}
