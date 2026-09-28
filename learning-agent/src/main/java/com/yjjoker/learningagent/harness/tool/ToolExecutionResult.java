package com.yjjoker.learningagent.harness.tool;

import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import com.yjjoker.learningagent.harness.memory.model.MemoryWriteReceipt;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 工具结果只表示真实执行成功或失败；是否需要审批由前置 Hook 决定。
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

    // 只供后端核对真实目标，不让普通工具消息序列化出数据库主键和归属。
    @Getter(AccessLevel.NONE)
    private final MemoryWriteReceipt writeReceipt;

    // 普通工具成功不代表发生了记忆写入。
    public static ToolExecutionResult success(String content) {
        return new ToolExecutionResult(true, content, null, null);
    }

    // 记忆工具先调用事务服务，等事务正常返回后再报告写入成功。
    public static ToolExecutionResult memoryWriteSuccess(String content, MemoryWriteReceipt receipt) {
        if (receipt == null || TransactionSynchronizationManager.isActualTransactionActive()) {
            // 事务尚未提交时不能提前发出成功凭据；后续异常仍可能导致回滚。
            throw new IllegalStateException("记忆写入成功必须在事务结束后携带实际写入凭据");
        }
        return new ToolExecutionResult(true, content, null, receipt);
    }

    // 业务失败不携带成功凭据，不能据此宣称数据已经修改。
    public static ToolExecutionResult failure(String errorCode, String message, boolean retryable) {
        return failure(HarnessError.of(
                errorCode,
                message,
                retryable,
                HarnessErrorSource.TOOL
        ));
    }

    // 保留统一错误对象，同时明确没有可确认的写入结果。
    public static ToolExecutionResult failure(HarnessError error) {
        if (error == null) {
            throw new IllegalArgumentException("工具失败结果必须包含 HarnessError");
        }
        return new ToolExecutionResult(false, null, error, null);
    }

    // 保留原有 JSON 字段，避免模型协议因内部错误模型升级而变化。
    public String getErrorCode() {
        return error == null ? null : error.getErrorCode();
    }

    // 对外保持原有失败提示字段。
    public String getMessage() {
        return error == null ? null : error.getMessage();
    }

    // 告诉调用者当前业务错误是否允许修正后重试。
    public boolean isRetryable() {
        return error != null && error.isRetryable();
    }

    // 代码内部需要错误来源时使用；方法名不是 JavaBean getter，避免重复序列化嵌套 error。
    public HarnessError error() {
        return error;
    }

    // 不使用 get 前缀，防止 Jackson 把内部凭据自动加入模型工具响应。
    public MemoryWriteReceipt memoryWriteReceipt() {
        return writeReceipt;
    }
}
