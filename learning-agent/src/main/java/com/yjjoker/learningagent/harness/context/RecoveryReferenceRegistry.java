package com.yjjoker.learningagent.harness.context;

import com.yjjoker.learningagent.harness.model.TaskReferenceScope;
import lombok.extern.slf4j.Slf4j;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 保存本次 Agent Loop 中“短引用 -> 真实 toolCallId”的映射。
// 引用带完整任务标识，旧任务的编号不能在新任务里指向另一份结果。
@Slf4j
public class RecoveryReferenceRegistry {

    private final Map<String, String> toolCallIdByReference = new LinkedHashMap<>();
    private final String referenceScope;
    private int nextReferenceNumber = 1;

    // 独立使用时分配新任务标识，不能让不同注册表都生成同一个 result_1。
    public RecoveryReferenceRegistry() {
        this(UUID.randomUUID().toString());
    }

    // Harness 使用逻辑任务 runId，审批前后不因换线程而更换引用范围。
    public RecoveryReferenceRegistry(String runId) {
        referenceScope = TaskReferenceScope.fromRunId(runId);
    }

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
        String reference = "result_" + referenceScope + "_" + nextReferenceNumber++;
        toolCallIdByReference.put(reference, toolCallId);
        log.debug("已注册任务恢复引用，reference={}，referenceCount={}", reference, toolCallIdByReference.size());
        return reference;
    }

    // 按完整引用匹配，不能去掉任务标识后仅按末尾数字查找。
    public String resolve(String reference) {
        return toolCallIdByReference.get(reference);
    }

    // 给模型列出当前仍有效的引用，不暴露真实调用编号。
    public List<String> references() {
        return List.copyOf(toolCallIdByReference.keySet());
    }

    // 保存引用和计数器，暂停后继续使用模型已经见过的 result_N。
    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(toolCallIdByReference);
    }

    // 返回下一个可分配的编号，不能用当前 Map 大小代替。
    public int nextNumber() {
        return nextReferenceNumber;
    }

    // 只恢复服务端检查点，不接受模型传入一张新的映射表。
    public void restore(Map<String, String> references, int nextNumber) {
        // 先检查所有编号，再替换映射，避免损坏数据导致恢复一半。
        if (references == null || nextNumber < 1) {
            throw new IllegalArgumentException("恢复引用检查点不完整");
        }
        for (var entry : references.entrySet()) {
            int number = TaskReferenceScope.restoredNumber(entry.getKey(), "result", referenceScope);
            if (number >= nextNumber || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("恢复引用编号或目标不合法");
            }
        }
        toolCallIdByReference.clear();
        toolCallIdByReference.putAll(references);
        nextReferenceNumber = nextNumber;
        log.info("已恢复原任务的工具引用，referenceCount={}，nextNumber={}", references.size(), nextNumber);
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
