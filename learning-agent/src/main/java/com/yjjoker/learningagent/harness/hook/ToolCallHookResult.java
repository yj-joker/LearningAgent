package com.yjjoker.learningagent.harness.hook;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

// beforeToolExecution Hook 使用这个对象明确表示“允许”或“拒绝”。
// 预期内的校验失败不再依赖抛异常，Harness 可以据此选择重试或直接结束。
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ToolCallHookResult {

    // true 表示允许，false 表示拒绝
    private final boolean allowed;

    // 拒绝时使用稳定的错误编码，模型和日志不需要解析自然语言来判断原因。
    private final String errorCode;

    // message 必须是可以安全交给模型或用户的信息，不能包含权限规则、SQL 或异常堆栈。
    private final String message;

    // true 表示模型调整参数后可以再次尝试；false 表示继续请求模型也无法解决。
    private final boolean retryable;

    public static ToolCallHookResult allow() {
        return new ToolCallHookResult(true, null, null, false);
    }

    public static ToolCallHookResult reject(String errorCode, String message, boolean retryable) {
        return new ToolCallHookResult(false, errorCode, message, retryable);
    }
}
