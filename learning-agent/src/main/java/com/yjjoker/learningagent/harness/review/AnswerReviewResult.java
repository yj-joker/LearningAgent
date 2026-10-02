package com.yjjoker.learningagent.harness.review;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 审查意见不是操作授权；是否执行工具仍由 Harness、业务校验和用户审批决定。
@Getter
@AllArgsConstructor
public class AnswerReviewResult {
    public enum Action {
        PASS, REWRITE, CONTINUE, CLARIFY,
        // 只由后端产生，表示审查失败或证据无法完整读取。
        UNAVAILABLE
    }

    private final Action action;
    private final String reason;
    private final String instruction;

    // 审查失败时不默认放行，避免把未经核实的成功声明发给用户。
    public static AnswerReviewResult unavailable() {
        return new AnswerReviewResult(Action.UNAVAILABLE, "审查暂不可用", "");
    }
}
