package com.yjjoker.learningagent.harness.context.impl;

import com.yjjoker.learningagent.harness.context.OriginalToolResultStore;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

// 保存当前 Agent Loop 刚产生的完整工具结果。
// 内存读取避免工具刚执行完就立刻访问数据库；数据库仍保存最终完整历史，负责跨请求恢复。
@Component
public class InMemoryOriginalToolResultStoreImpl implements OriginalToolResultStore {

    // 每个请求线程拥有独立 Map，因此不同用户不能通过这份临时缓存互相读取结果。
    // ConcurrentHashMap 让同一请求内未来出现并发工具调用时也能安全写入。
    private final ThreadLocal<Map<String, String>> resultsByCallId =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    @Override
    public void save(String toolCallId, String originalResult) {
        // 根据 toolCallId 保存原始结果到当前线程的独立空间。
        resultsByCallId.get().put(toolCallId, originalResult);
    }

    @Override
    public String read(String toolCallId, int offset, int limit) {
        String result = resultsByCallId.get().get(toolCallId);
        if (result == null || offset >= result.length()) {
            // 找不到或 offset 已经到达末尾时返回空串，由上层根据 totalLength 判断是否还有内容。
            return "";
        }
        int safeOffset = Math.max(offset, 0);
        int end = Math.min(safeOffset + limit, result.length());
        // 返回模型本次需要的片段。
        return result.substring(safeOffset, end);
    }

    @Override
    public int length(String toolCallId) {
        // 检查当前线程的独立空间当中是否保存了这个调用 ID 的结果。
        String result = resultsByCallId.get().get(toolCallId);
        return result == null ? -1 : result.length();
    }

    @Override
    public void clear() {
        // remove 比 set(emptyMap) 更彻底，避免线程池复用线程时残留上一个请求的数据。
        resultsByCallId.remove();
    }
}
