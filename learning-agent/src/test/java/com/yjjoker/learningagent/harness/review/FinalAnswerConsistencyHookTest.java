package com.yjjoker.learningagent.harness.review;

import com.yjjoker.learningagent.harness.hook.*;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Hook 只做调度建议；不执行工具，不把审查意见变成授权。
class FinalAnswerConsistencyHookTest {
    private final AnswerReviewService review = mock(AnswerReviewService.class);
    private final FinalAnswerConsistencyHook hook = new FinalAnswerConsistencyHook(review);
    private final AnswerReviewRequest request = new AnswerReviewRequest("讲解集合", "步骤完成", List.of(), null);

    // 普通问答不增加审查调用。
    @Test
    void skipsChat() {
        assertEquals(FinalAnswerHookResult.Action.ALLOW, hook.beforeFinalAnswer(new AgentRunContext(), request).getAction());
        verifyNoInteractions(review);
    }

    // 没有执行任何工具也必须审查，改口和澄清均没有工具能力。
    @Test
    void reviewsZeroToolAnswerAndUsesTextOnlyCorrection() {
        when(review.review(any(), any())).thenReturn(new AnswerReviewResult(AnswerReviewResult.Action.REWRITE, "无依据", "修改说法"))
                .thenReturn(new AnswerReviewResult(AnswerReviewResult.Action.CLARIFY, "指代不明", "询问用户"));
        assertTrue(hook.beforeFinalAnswer(focus(), request).isRewriteWithoutTools());
        assertTrue(hook.beforeFinalAnswer(focus(), request).isRewriteWithoutTools());
        verify(review, times(2)).review(any(), any());
    }

    // 用户拒绝后，即使审查模型错误建议继续，也只能改写说明，不能绕过拒绝。
    @Test
    void rejectionBlocksReviewerSuggestedContinuation() {
        AgentRunContext context = focus();
        ToolCall call = new ToolCall("id", "update_task_progress", "{}");
        context.requestToolExecution(call);
        context.rejectToolExecution(call, ToolExecutionResult.failure("APPROVAL_REJECTED", "用户拒绝", false));
        when(review.review(any(), any())).thenReturn(new AnswerReviewResult(AnswerReviewResult.Action.CONTINUE, "误判遗漏", "再执行"));
        assertTrue(hook.beforeFinalAnswer(context, request).isRewriteWithoutTools());
    }

    // 纠正预算用尽后，审查通过仍可放行，审查不通过则不能再次请求主模型。
    @Test
    void boundsCorrectionsAndAllowsSuccessfulRecheck() {
        AgentRunContext context = focus();
        context.restoreAnswerReviewCorrections(1);
        when(review.review(any(), any())).thenReturn(new AnswerReviewResult(AnswerReviewResult.Action.PASS, "真实", ""))
                .thenReturn(new AnswerReviewResult(AnswerReviewResult.Action.CONTINUE, "遗漏", "继续"));
        assertEquals(FinalAnswerHookResult.Action.ALLOW, hook.beforeFinalAnswer(context, request).getAction());
        assertTrue(hook.beforeFinalAnswer(context, request).isReplaceAnswer());
        assertEquals(1, context.getAnswerReviewCorrections());
    }

    // 审查实现异常也保守返回，不影响已经发生的工具写入事实。
    @Test
    void serviceExceptionDoesNotAllowDraft() {
        when(review.review(any(), any())).thenThrow(new IllegalStateException("模拟故障"));
        assertTrue(hook.beforeFinalAnswer(focus(), request).isReplaceAnswer());
    }

    // 创建独立请求上下文，避免测试之间共用纠正次数。
    private AgentRunContext focus() {
        AgentRunContext context = new AgentRunContext();
        context.bindMode(AgentMode.FOCUS);
        return context;
    }
}
