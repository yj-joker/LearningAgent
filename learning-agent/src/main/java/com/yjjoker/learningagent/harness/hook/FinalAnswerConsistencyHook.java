package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.harness.review.AnswerReviewRequest;
import com.yjjoker.learningagent.harness.review.AnswerReviewResult;
import com.yjjoker.learningagent.harness.review.AnswerReviewService;
import com.yjjoker.learningagent.harness.tool.ToolExecutionRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 拟回复落库前核对执行事实；零工具调用也要审查，不能漏掉“只说完成”的情况。
@Component
@RequiredArgsConstructor
@Slf4j
public class FinalAnswerConsistencyHook implements AgentHook {
    public static final String SAFE_ANSWER =
            "本轮回答未通过执行事实核对，暂不展示未经确认的完成结论。请以实际进度和审批状态为准；"
                    + "已经执行的操作不会因审查失败而自动撤销。";
    private final AnswerReviewService reviewService;

    // 普通问答保持原行为；所有专注模式拟回复都审查，不按调用过的工具筛选。
    @Override
    public FinalAnswerHookResult beforeFinalAnswer(AgentRunContext context, AnswerReviewRequest request) {
        if (context.getMode() != AgentMode.FOCUS) return FinalAnswerHookResult.allow();
        AnswerReviewResult result;
        try {
            result = reviewService.review(context, request);
        } catch (RuntimeException exception) {
            // 其他审查实现也可能异常；无论实现方式都不能失败后直接放行。
            log.warn("审查服务异常，使用保守回答，runId={}，errorType={}",
                    context.getRunId(), exception.getClass().getSimpleName());
            return FinalAnswerHookResult.replaceAnswer(SAFE_ANSWER);
        }
        if (result == null || result.getAction() == null
                || result.getAction() == AnswerReviewResult.Action.UNAVAILABLE) {
            return FinalAnswerHookResult.replaceAnswer(SAFE_ANSWER);
        }
        if (result.getAction() == AnswerReviewResult.Action.PASS) return FinalAnswerHookResult.allow();
        if (!context.tryUseAnswerReviewCorrection()) {
            log.warn("回答审查纠正次数已用尽，runId={}，action={}", context.getRunId(), result.getAction());
            return FinalAnswerHookResult.replaceAnswer(SAFE_ANSWER);
        }
        // 有拒绝、不可重试失败或未知结果时只解释现状，不能补执行绕过它。
        boolean blocked = context.getToolExecutions().stream().anyMatch(record ->
                record.getStatus() == ToolExecutionRecord.Status.REJECTED
                        || record.getStatus() == ToolExecutionRecord.Status.ERROR
                        || (record.getStatus() == ToolExecutionRecord.Status.FAILED
                        && (record.getResult() == null || !record.getResult().isRetryable())));
        log.info("回答审查要求纠正，runId={}，action={}，continuationBlocked={}，corrections={}",
                context.getRunId(), result.getAction(), blocked, context.getAnswerReviewCorrections());
        if (result.getAction() == AnswerReviewResult.Action.CONTINUE && !blocked) {
            return FinalAnswerHookResult.retryModel(result.getInstruction());
        }
        String instruction = blocked && result.getAction() == AnswerReviewResult.Action.CONTINUE
                ? "本轮存在拒绝、不可重试失败或不确定结果。只如实解释现状或询问用户，不声称成功、不重复申请。"
                : result.getInstruction();
        return FinalAnswerHookResult.rewriteWithoutTools(instruction);
    }
}
