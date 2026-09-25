package com.yjjoker.learningagent.harness.error;

import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import lombok.Getter;

// HarnessException 包装无法作为 tool 结果继续处理的系统级错误。
// 继承旧的服务异常，保证现有统一异常处理器仍能正常返回 HTTP 错误。
@Getter
public class HarnessException extends LearningAgentServiceException {

    private final HarnessError error;

    public HarnessException(HarnessError error) {
        super(error.getMessage());
        this.error = error;
    }

    public HarnessException(HarnessError error, Throwable cause) {
        super(error.getMessage(), cause);
        this.error = error;
    }

    public String getErrorCode() {
        return error.getErrorCode();
    }
}
