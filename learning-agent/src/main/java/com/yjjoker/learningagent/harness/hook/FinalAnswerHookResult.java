package com.yjjoker.learningagent.harness.hook;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 最终回答 Hook 的处理结果；Harness 根据结果决定放行、重新请求模型或替换回答。
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FinalAnswerHookResult {
    public enum Action {
        ALLOW,
        RETRY_MODEL,
        REWRITE_WITHOUT_TOOLS,
        REPLACE_ANSWER
    }

    private final Action action;
    // 保存为什么需要纠正，避免 Hook 只转发修改建议而丢掉问题原因。
    private final String reason;
    // 纠正时是修改建议，替换回答时是后端给用户的保护性说明。
    private final String message;

    // 所有一致性检查通过，允许保存并返回模型回答。
    public static FinalAnswerHookResult allow() {
        return new FinalAnswerHookResult(Action.ALLOW, null, null);
    }

    // 确认用户请求遗漏必要行动时，继续原工具循环；这不是操作授权。
    public static FinalAnswerHookResult retryModel(String reason, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("模型重试提示不能为空");
        }
        // 没有提供原因时保留为空，不替审查模型编造判断依据。
        return new FinalAnswerHookResult(Action.RETRY_MODEL, reason == null ? "" : reason, instruction);
    }

    // 改口或澄清只允许生成文本，不能为了让错误回答成立而补写数据库。
    public static FinalAnswerHookResult rewriteWithoutTools(String reason, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("回答纠正提示不能为空");
        }
        return new FinalAnswerHookResult(Action.REWRITE_WITHOUT_TOOLS, reason == null ? "" : reason, instruction);
    }

    // Harness 据此选择不带业务工具的模型请求。
    public boolean isRewriteWithoutTools() {
        return action == Action.REWRITE_WITHOUT_TOOLS;
    }

    // 重试次数用尽后返回后端事实，避免把模型口头描述当作执行结果。
    public static FinalAnswerHookResult replaceAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("保护性回答不能为空");
        }
        return new FinalAnswerHookResult(Action.REPLACE_ANSWER, null, answer);
    }

    // 判断是否需要重新请求模型。
    public boolean isRetryModel() {
        return action == Action.RETRY_MODEL;
    }

    // 判断是否需要替换模型原始回答。
    public boolean isReplaceAnswer() {
        return action == Action.REPLACE_ANSWER;
    }
}
