package com.yjjoker.learningagent.harness.impl;

import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.harness.context.OriginalToolResultStore;
import com.yjjoker.learningagent.repository.LearningSessionMessageRepository;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

//默认注入该实现
@Primary
@Component
@AllArgsConstructor
public class DatabaseOriginalToolResultStoreImpl implements OriginalToolResultStore {

    // 注入内存工具结果存储类
    private final InMemoryOriginalToolResultStoreImpl memoryStore;

    private final LearningSessionMessageRepository messageRepository;

    // 当前线程对应的会话 ID由 Harness 设置，数据库查询必须带上它进行会话隔离。
    private final ThreadLocal<Long> currentSessionId = new ThreadLocal<>();


    @Override
    public void beginSession(Long sessionId) {
        // 新请求开始时记录会话范围；内存实现的线程空间会按需自动创建。
        currentSessionId.set(sessionId);
    }

    @Override
    public void save(String toolCallId, String originalResult) {
        // 新执行结果先进入内存；本轮最终消息由 ConversationMemoryService 统一保存到数据库。
        memoryStore.save(toolCallId, originalResult);
    }

    @Override
    public String read(String toolCallId, int offset, int limit) {
        // 从当前线程的内存实现中读取结果。检查长度是因为合法的空结果也可能返回空串。
        int memoryLength = memoryStore.length(toolCallId);

        // 直接从内存中读取结果。不管是否在当前 Agent Loop 中
        String result = memoryStore.read(toolCallId, offset, limit);
        if (memoryLength >= 0) {
            // memoryLength >= 0 表示请求访问的片段在本次 Agent Loop 中，直接返回，不访问数据库。
            return result;
        }

        // 是否是在一次Agent Loop 中。
        String originalResult = loadFromDatabase(toolCallId);
        if (originalResult == null) {
            // null 表示调用 ID 不存在，交给上层转换为明确的不可恢复错误。
            return "";
        }
        // 回填缓存，后续读取同一个结果的下一段时不需要再次查数据库。
        memoryStore.save(toolCallId, originalResult);
        return memoryStore.read(toolCallId, offset, limit);
    }

    @Override
    public int length(String toolCallId) {
        int memoryLength = memoryStore.length(toolCallId);
        if (memoryLength >= 0) {
            // 已经在内存中时直接返回长度，避免一次恢复动作产生两次数据库访问。
            return memoryLength;
        }

        // length 也支持数据库回退，因为恢复工具会先询问总长度再读取片段。
        String originalResult = loadFromDatabase(toolCallId);
        if (originalResult == null) {
            return -1;
        }
        // 回填缓存，后续读取同一个结果的下一段时不需要再次查数据库。
        memoryStore.save(toolCallId, originalResult);
        return originalResult.length();
    }

    @Override
    public void clear() {
        // 同时清理组合实现和内部实现，避免线程池复用线程时残留会话 ID或工具结果。
        memoryStore.clear();
        currentSessionId.remove();
    }

    // 从数据库中加载工具调用原始结果
    private String loadFromDatabase(String toolCallId) {
        Long sessionId = currentSessionId.get();
        if (sessionId == null) {
            // 没有 Harness 设置的会话范围时，禁止查询数据库，避免出现跨会话读取风险。
            return null;
        }
        // Repository 查询同时使用 sessionId 和 toolCallId，不能只按调用 ID 查询。
        LearningSessionMessage message = messageRepository.findToolResult(sessionId, toolCallId);
        return message == null ? null : message.getContent();
    }
}
