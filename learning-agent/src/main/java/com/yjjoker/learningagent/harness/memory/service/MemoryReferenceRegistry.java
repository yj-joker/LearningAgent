package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.memory.model.MemoryReference;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionContext;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionTarget;
import com.yjjoker.learningagent.harness.memory.model.MemoryIndexSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

// 为一次 AgentLoop 建立 memoryRef 到真实记忆 ID 的临时映射。
// ThreadLocal 保证同一应用进程中的并发请求不会互相读取引用。
@Component
@Slf4j
public class MemoryReferenceRegistry {

    // 每个线程保存自己的当前请求映射，避免并发用户共享 memoryRef。
    private final ThreadLocal<RunReferences> currentRun =
            ThreadLocal.withInitial(RunReferences::new);

    // 开始新一轮 Agent 请求时清空旧映射，避免引用跨请求复用。
    public void beginRun(Long userId, Long sessionId) {
        beginRun(userId, sessionId, null);
    }

    // 由 Harness 保存本轮原文；工具参数不能替换这份用户依据。
    public void beginRun(Long userId, Long sessionId, String userMessage) {
        currentRun.set(new RunReferences(userId, sessionId, userMessage));
        log.debug("记忆引用映射已开始，userId={}，sessionId={}", userId, sessionId);
    }

    // 注册一条长期记忆并返回模型可见的短引用。
    public String registerUserMemory(UserMemory memory) {
        String ref = register(
                new MemoryReference(MemoryScope.USER, memory.getUserId(), memory.getId())
        );
        currentRun.get().snapshots.put(ref, new MemoryExtractionTarget(ref, MemoryScope.USER,
                memory.getUserId(), memory.getId(), memory.getMemoryKey(), memory.getMemoryTopic(),
                memory.getMemorySummary(), memory.getUpdatedAt()));
        return ref;
    }

    // 注册一条会话记忆并返回模型可见的短引用。
    public String registerSessionMemory(SessionMemory memory) {
        String ref = register(
                new MemoryReference(MemoryScope.SESSION, memory.getSessionId(), memory.getId())
        );
        currentRun.get().snapshots.put(ref, new MemoryExtractionTarget(ref, MemoryScope.SESSION,
                memory.getSessionId(), memory.getId(), memory.getMemoryKey(), memory.getMemoryTopic(),
                memory.getMemorySummary(), memory.getUpdatedAt()));
        return ref;
    }

    // 根据模型返回的 memoryRef 查找服务端真实目标；找不到时返回 null。
    public MemoryReference resolve(String memoryRef) {
        if (memoryRef == null || memoryRef.isBlank()) {
            return null;
        }
        return currentRun.get().references.get(memoryRef);
    }

    // 返回当前 AgentLoop 的用户范围，记忆写工具用它校验 USER 记忆归属。
    public Long currentUserId() {
        return currentRun.get().userId;
    }

    // 返回当前 AgentLoop 的会话范围，记忆写工具用它校验 SESSION 记忆归属。
    public Long currentSessionId() {
        return currentRun.get().sessionId;
    }

    // 删除已软删除记忆的临时引用，避免同一轮继续读取已经失效的目标。
    public void remove(String memoryRef) {
        if (memoryRef != null && !memoryRef.isBlank()) {
            currentRun.get().references.remove(memoryRef);
            currentRun.get().snapshots.remove(memoryRef);
            log.debug("记忆引用已移除，memoryRef={}", memoryRef);
        }
    }

    // 返回主模型看到的目标快照；保存服务加锁后检查这些字段是否已经变化。
    public MemoryExtractionContext toolContext() {
        RunReferences run = currentRun.get();
        return new MemoryExtractionContext(run.userId, run.sessionId, new ArrayList<>(run.snapshots.values()));
    }

    // 原文来自本次 HTTP 请求，不取历史对话或模型补写的用户消息。
    public String currentUserMessage() {
        return currentRun.get().userMessage;
    }

    // 刷新后保留原编号；失效编号不会再分配给其他记忆。
    public void refresh(MemoryIndexSnapshot index) {
        Set<String> activeRefs = new HashSet<>();
        index.getUserMemories().forEach(memory -> activeRefs.add(registerUserMemory(memory)));
        index.getSessionMemories().forEach(memory -> activeRefs.add(registerSessionMemory(memory)));
        currentRun.get().references.keySet().retainAll(activeRefs);
        currentRun.get().snapshots.keySet().retainAll(activeRefs);
        log.info("本轮记忆引用已刷新，sessionId={}，memoryCount={}", currentSessionId(), activeRefs.size());
    }

    // 任务结束后释放线程变量，防止线程池复用时残留上一次请求的映射。
    public void clear() {
        currentRun.remove();
    }

    // 相同数据库目标复用本轮编号；新增目标才消耗下一个编号。
    private String register(MemoryReference reference) {
        RunReferences runReferences = currentRun.get();
        // 防止错误的索引对象被注册到当前用户或当前会话的映射中。
        if (reference.getScope() == MemoryScope.USER
                && !Objects.equals(reference.getOwnerId(), runReferences.userId)) {
            throw new IllegalArgumentException("长期记忆不属于当前用户");
        }
        if (reference.getScope() == MemoryScope.SESSION
                && !Objects.equals(reference.getOwnerId(), runReferences.sessionId)) {
            throw new IllegalArgumentException("会话记忆不属于当前会话");
        }
        for (var entry : runReferences.references.entrySet()) {
            MemoryReference existing = entry.getValue();
            if (existing.getScope() == reference.getScope()
                    && Objects.equals(existing.getOwnerId(), reference.getOwnerId())
                    && Objects.equals(existing.getMemoryId(), reference.getMemoryId())) {
                return entry.getKey();
            }
        }
        String memoryRef = "memory_" + runReferences.nextNumber++;
        runReferences.references.put(memoryRef, reference);
        log.debug("记忆引用注册成功，memoryRef={}，scope={}，memoryId={}",
                memoryRef, reference.getScope(), reference.getMemoryId());
        return memoryRef;
    }

    // 一次请求的映射表和编号生成器。
    private static class RunReferences {

        // 当前请求的用户范围，用于校验长期记忆归属。
        private final Long userId;
        // 当前请求的会话范围，用于校验会话记忆归属。
        private final Long sessionId;
        private final String userMessage;
        // 只保存可见摘要和版本信息，不提前加载完整正文。
        private final Map<String, MemoryExtractionTarget> snapshots = new LinkedHashMap<>();
        // 按生成顺序保存 memoryRef 到真实目标的映射。
        private final Map<String, MemoryReference> references = new LinkedHashMap<>();
        // 只在当前请求内递增，重新请求会从 memory_1 重新开始。
        private int nextNumber = 1;

        private RunReferences() {
            this(null, null, null);
        }

        // 每次请求新建容器，结束时由 clear 释放。
        private RunReferences(Long userId, Long sessionId, String userMessage) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.userMessage = userMessage;
        }
    }
}
