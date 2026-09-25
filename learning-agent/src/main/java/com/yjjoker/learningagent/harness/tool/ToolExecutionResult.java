package com.yjjoker.learningagent.harness.tool;

import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 工具统一用这个对象表示执行结果，Harness 因此能明确区分“成功”和“可恢复的失败”。
// 预期内的业务失败进入 HarnessError；数据库断连、代码错误等系统异常仍交给 HarnessException。
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ToolExecutionResult {

    // true 表示工具已经正常完成；查无数据也属于正常完成，而不是程序异常。
    private final boolean success;

    // 工具成功时返回的数据，Harness 会把它交给 LLM 组织最终回答。
    private final String content;

    // 失败信息集中放在统一错误对象中，避免工具和 Hook 各自定义错误字段。
    @Getter(AccessLevel.NONE)
    private final HarnessError error;

    public static ToolExecutionResult success(String content) {
        return new ToolExecutionResult(true, content, null);
    }

    public static ToolExecutionResult failure(String errorCode, String message, boolean retryable) {
        return failure(HarnessError.of(
                errorCode,
                message,
                retryable,
                HarnessErrorSource.TOOL
        ));
    }

    public static ToolExecutionResult failure(HarnessError error) {
        if (error == null) {
            throw new IllegalArgumentException("工具失败结果必须包含 HarnessError");
        }
        return new ToolExecutionResult(false, null, error);
    }

    // 保留原有 JSON 字段，避免模型协议因内部错误模型升级而变化。
    public String getErrorCode() {
        return error == null ? null : error.getErrorCode();
    }

    public String getMessage() {
        return error == null ? null : error.getMessage();
    }

    public boolean isRetryable() {
        return error != null && error.isRetryable();
    }

    // 代码内部需要错误来源时使用；方法名不是 JavaBean getter，避免重复序列化嵌套 error。
    public HarnessError error() {
        return error;
    }
}
