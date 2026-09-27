package com.yjjoker.learningagent.harness.hook;

import lombok.Getter;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.tool.ToolExecutionRecord;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    // 记录由当前请求独占；普通日志和后续提取都从这里读取，不扫描聊天正文猜执行状态。
    private final List<ToolExecutionRecord> toolExecutions = new ArrayList<>();
    // 发现记录不一致时保守停止自动提取，不能把记录失败当成没有调用过工具。
    private boolean toolHistoryComplete = true;

    // completed 表示 Agent Loop 已经结束；successful 用来区分正常回答和异常终止。
    private boolean completed;
    private boolean successful;

    // 异常结束时只记录异常类型，不在上下文中保存可能包含敏感信息的异常消息。
    private String failureType;

    // 记录工具调用。
    public void recordToolExecution(String toolName) {
        executedToolNames.add(toolName);
    }

    // 在查找工具和执行前置 Hook 之前登记，保证未知工具和拒绝分支也有记录。
    public void requestToolExecution(ToolCall call) {
        if (!toolExecutions.isEmpty() && !toolExecutions.getLast().isFinished()) {
            toolHistoryComplete = false;
            throw new IllegalStateException("上一条工具轨迹尚未完成");
        }
        toolExecutions.add(ToolExecutionRecord.requested(toolExecutions.size() + 1, call));
    }

    // 分类来自注册表中的 Java 工具，而不是模型提交的参数。
    public void classifyToolExecution(ToolCall call, boolean memoryWriteTool) {
        replaceCurrent(call, current(call).withMemoryWriteTool(memoryWriteTool));
    }

    // 所有前置检查通过后才标记 STARTED，拒绝的请求不会被当成执行过。
    public void startToolExecution(ToolCall call) {
        replaceCurrent(call, current(call).withOutcome(ToolExecutionRecord.Status.STARTED, null, null));
    }

    // 后置 Hook 保存工具实际返回的结果；查无数据但 success=true 仍属于成功。
    public void completeToolExecution(ToolCall call, ToolExecutionResult result) {
        if (result == null) {
            toolHistoryComplete = false;
            throw new IllegalArgumentException("工具轨迹必须包含实际返回结果");
        }
        replaceCurrent(call, current(call).withOutcome(result.isSuccess()
                ? ToolExecutionRecord.Status.SUCCEEDED : ToolExecutionRecord.Status.FAILED, result, null));
    }

    // 工具没有执行时记录拒绝结果，不触发 afterToolExecution。
    public void rejectToolExecution(ToolCall call, ToolExecutionResult result) {
        replaceCurrent(call, current(call).withOutcome(ToolExecutionRecord.Status.REJECTED, result, null));
    }

    // 核对 Hook 保存的是当前调用的实际结果；缺少记录时由 Harness 补齐。
    public boolean hasToolOutcome(ToolCall call, ToolExecutionResult result) {
        ToolExecutionRecord record = current(call);
        return record.isFinished() && record.getResult() == result
                && record.getStatus() == (result.isSuccess()
                ? ToolExecutionRecord.Status.SUCCEEDED : ToolExecutionRecord.Status.FAILED);
    }

    // 返回列表副本；每条记录也是不可变对象，避免后续更新改变旧快照。
    public List<ToolExecutionRecord> getToolExecutions() {
        return List.copyOf(toolExecutions);
    }

    // 提取前检查记录是否完整；没有调用工具时空列表也属于完整轨迹。
    public boolean hasCompleteToolHistory() {
        return toolHistoryComplete && toolExecutions.stream().allMatch(ToolExecutionRecord::isFinished);
    }

    // 标记记录缺失或不一致，主回答仍可返回，但本轮跳过自动提取记忆。
    public void markToolHistoryIncomplete() {
        toolHistoryComplete = false;
    }

    // Harness 当前串行执行工具，因此只允许更新最后一个正在处理的调用。
    private ToolExecutionRecord current(ToolCall call) {
        if (toolExecutions.isEmpty()
                || !Objects.equals(toolExecutions.getLast().getToolCallId(), call.id())
                || !Objects.equals(toolExecutions.getLast().getToolName(), call.name())) {
            toolHistoryComplete = false;
            throw new IllegalStateException("工具轨迹与当前调用不一致");
        }
        return toolExecutions.getLast();
    }

    // 替换最后一条不可变记录，调用顺序和之前完成的记录都保持不变。
    private void replaceCurrent(ToolCall call, ToolExecutionRecord replacement) {
        current(call);
        toolExecutions.set(toolExecutions.size() - 1, replacement);
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
        // 异常可能发生在前置检查或工具内部；不能据此宣称数据库已回滚。
        if (!toolExecutions.isEmpty() && !toolExecutions.getLast().isFinished()) {
            ToolExecutionRecord pending = toolExecutions.getLast();
            toolExecutions.set(toolExecutions.size() - 1,
                    pending.withOutcome(ToolExecutionRecord.Status.ERROR, null, failureType));
        }
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
