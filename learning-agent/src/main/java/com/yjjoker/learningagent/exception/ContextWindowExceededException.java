package com.yjjoker.learningagent.exception;

import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorCode;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import com.yjjoker.learningagent.harness.error.HarnessException;

// 工具结果压缩和当前阶段摘要都无法容纳上下文时使用。
public class ContextWindowExceededException extends HarnessException {

    public ContextWindowExceededException(String message) {
        super(HarnessError.of(
                HarnessErrorCode.CONTEXT_WINDOW_EXCEEDED,
                message,
                false,
                HarnessErrorSource.CONTEXT
        ));
    }
}
