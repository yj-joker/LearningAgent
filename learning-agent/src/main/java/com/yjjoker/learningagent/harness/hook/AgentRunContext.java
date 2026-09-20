package com.yjjoker.learningagent.harness.hook;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 每调用一次 Harness.run()，就会创建一个独立的任务上下文。
// 保存本次任务的运行信息，避免多个并发请求共用 Hook 时互相污染数据。
@Getter
public class AgentRunContext {

    // runId 用来把同一次任务产生的多条日志关联起来。
    private final String runId = UUID.randomUUID().toString();

    // 使用单调递增的纳秒时间计算耗时，不受系统时钟被校准的影响。
    private final long startedAtNanos = System.nanoTime();

    // 只记录已经通过 before Hook、即将真正执行的工具名称。
    private final List<String> executedToolNames = new ArrayList<>();

    // completed 表示 Agent Loop 已经结束；successful 用来区分正常回答和异常终止。
    private boolean completed;
    private boolean successful;

    // 异常结束时只记录异常类型，不在上下文中保存可能包含敏感信息的异常消息。
    private String failureType;

    // 记录工具调用。
    public void recordToolExecution(String toolName) {
        executedToolNames.add(toolName);
    }

    // 标记任务正常结束。
    public void markSucceeded() {
        completed = true;
        successful = true;
    }

    // 标记任务异常结束。
    public void markFailed(RuntimeException exception) {
        completed = true;
        successful = false;
        failureType = exception.getClass().getSimpleName();
    }

    // 计算任务耗时，单位毫秒。
    public long getElapsedMilliseconds() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    // 返回工具调用记录的副本，防止 Hook 修改 Harness 保存的工具调用记录。
    public List<String> getExecutedToolNames() {
        return List.copyOf(executedToolNames);
    }
}
