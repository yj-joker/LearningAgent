package com.yjjoker.learningagent.harness.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 保存本次 Agent Loop 中“短引用 -> 真实 toolCallId”的映射。
// 模型只需要复制 result_1，随机的 call_xxx 由 Harness 自己管理。
public class RecoveryReferenceRegistry {

    private final Map<String, String> toolCallIdByReference = new LinkedHashMap<>();

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
        String reference = "result_" + (toolCallIdByReference.size() + 1);
        toolCallIdByReference.put(reference, toolCallId);
        return reference;
    }

    public String resolve(String reference) {
        return toolCallIdByReference.get(reference);
    }

    // 只有一个候选时，模型传错引用也可以安全兜底到唯一结果。
    public String onlyToolCallId() {
        return toolCallIdByReference.size() == 1
                ? toolCallIdByReference.values().iterator().next()
                : null;
    }

    public List<String> references() {
        return List.copyOf(toolCallIdByReference.keySet());
    }
}
