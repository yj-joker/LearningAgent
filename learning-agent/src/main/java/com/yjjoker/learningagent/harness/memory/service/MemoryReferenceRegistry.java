package com.yjjoker.learningagent.harness.memory.service;

import com.yjjoker.learningagent.entity.SessionMemory;
import com.yjjoker.learningagent.entity.UserMemory;
import com.yjjoker.learningagent.harness.memory.model.MemoryReference;
import com.yjjoker.learningagent.harness.memory.model.MemoryScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
        currentRun.set(new RunReferences(userId, sessionId));
        log.debug("记忆引用映射已开始，userId={}，sessionId={}", userId, sessionId);
    }

    // 注册一条长期记忆并返回模型可见的短引用。
    public String registerUserMemory(UserMemory memory) {
        return register(
                new MemoryReference(MemoryScope.USER, memory.getUserId(), memory.getId())
        );
    }

    // 注册一条会话记忆并返回模型可见的短引用。
    public String registerSessionMemory(SessionMemory memory) {
        return register(
                new MemoryReference(MemoryScope.SESSION, memory.getSessionId(), memory.getId())
        );
    }

    // 根据模型返回的 memoryRef 查找服务端真实目标；找不到时返回 null。
    public MemoryReference resolve(String memoryRef) {
        if (memoryRef == null || memoryRef.isBlank()) {
            return null;
        }
        return currentRun.get().references.get(memoryRef);
    }

    // 任务结束后释放线程变量，防止线程池复用时残留上一次请求的映射。
    public void clear() {
        currentRun.remove();
    }

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
        // 按生成顺序保存 memoryRef 到真实目标的映射。
        private final Map<String, MemoryReference> references = new LinkedHashMap<>();
        // 只在当前请求内递增，重新请求会从 memory_1 重新开始。
        private int nextNumber = 1;

        private RunReferences() {
            this(null, null);
        }

        private RunReferences(Long userId, Long sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
        }
    }
}
