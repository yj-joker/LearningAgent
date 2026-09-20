package com.yjjoker.learningagent.harness.tool;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 工具统一用这个对象表示执行结果，Harness 因此能明确区分“成功”和“可恢复的失败”。
// 数据库断连、代码错误等系统异常不放进这个对象，仍然交给 Harness 统一终止请求。
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ToolExecutionResult {

    // true 表示工具已经正常完成；查无数据也属于正常完成，而不是程序异常。
    private final boolean success;

    // 工具成功时返回的数据，Harness 会把它交给 LLM 组织最终回答。
    private final String content;

    // 工具失败时的稳定错误标识，方便 LLM 和程序区分不同失败原因。
    private final String errorCode;

    // 工具失败时给 LLM 阅读的说明，不应该包含 SQL、密码或异常堆栈。
    private final String message;

    // true 表示模型可以修改参数后再次调用工具，例如缺少 username。
    private final boolean retryable;

    public static ToolExecutionResult success(String content) {
        return new ToolExecutionResult(true, content, null, null, false);
    }

    public static ToolExecutionResult failure(String errorCode, String message, boolean retryable) {
        return new ToolExecutionResult(false, null, errorCode, message, retryable);
    }
}
