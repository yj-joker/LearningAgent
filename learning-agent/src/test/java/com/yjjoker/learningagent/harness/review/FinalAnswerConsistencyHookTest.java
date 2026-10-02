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
        FinalAnswerHookResult rewrite = hook.beforeFinalAnswer(focus(), request);
        assertTrue(rewrite.isRewriteWithoutTools());
        assertEquals("无依据", rewrite.getReason());
        assertEquals("修改说法", rewrite.getMessage());
        // 澄清同样保留原因，不能只告诉主模型“询问用户”。
        FinalAnswerHookResult clarify = hook.beforeFinalAnswer(focus(), request);
        assertTrue(clarify.isRewriteWithoutTools());
        assertEquals("指代不明", clarify.getReason());
        assertEquals("询问用户", clarify.getMessage());
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
        FinalAnswerHookResult result = hook.beforeFinalAnswer(context, request);
        assertTrue(result.isRewriteWithoutTools());
        // 执行方式、原因和建议一起改正，不能把相互矛盾的反馈交回主模型。
        assertEquals("本轮存在真实拒绝、不可重试失败或不确定结果，后端不允许继续工具调用。", result.getReason());
        assertEquals("只如实解释现状或询问用户，不声称成功、不重复申请。", result.getMessage());
    }

    // 预检失败未执行任何操作，审查要求修正后应回主循环，而不是限制成纯文字改写。
    @Test
    void validationFailureAllowsBoundedCorrection() {
        AgentRunContext context = focus();
        ToolCall call = new ToolCall("bad-input", "update_task_progress", "{}");
        context.requestToolExecution(call);
        context.failToolValidation(call, ToolExecutionResult.failure("INVALID_STEP_PROGRESS", "修正完成依据", true));
        when(review.review(any(), any())).thenReturn(new AnswerReviewResult(
                AnswerReviewResult.Action.CONTINUE, "参数可修正", "使用正确参数申请"));

        FinalAnswerHookResult result = hook.beforeFinalAnswer(context, request);
        assertEquals(FinalAnswerHookResult.Action.RETRY_MODEL, result.getAction());
        assertFalse(result.isRewriteWithoutTools());
        assertEquals("参数可修正", result.getReason());
        assertEquals("使用正确参数申请", result.getMessage());
        assertEquals(1, context.getAnswerReviewCorrections());
        assertTrue(hook.beforeFinalAnswer(context, request).isReplaceAnswer());
    }

    // 空原因不编造成事实，但修改建议仍是纠正请求的必要信息。
    @Test
    void normalizesMissingReasonAndRequiresInstruction() {
        assertEquals("", FinalAnswerHookResult.retryModel(null, "补上参数").getReason());
        assertEquals("", FinalAnswerHookResult.rewriteWithoutTools(null, "解释现状").getReason());
        assertThrows(IllegalArgumentException.class, () -> FinalAnswerHookResult.retryModel("原因", " "));
        assertThrows(IllegalArgumentException.class, () -> FinalAnswerHookResult.rewriteWithoutTools("原因", null));
    }

    // 真正拒绝不会因为同名工具的另一次成功而消失；但如实回答仍然可以通过。
    @Test
    void successDoesNotOverrideRejectionOrPreventTruthfulAnswer() {
        AgentRunContext context = focus();
        ToolCall denied = new ToolCall("denied", "write", "{\"target\":1}");
        context.requestToolExecution(denied);
        context.rejectToolExecution(denied, ToolExecutionResult.failure("NO_PERMISSION", "无权限", true));
        ToolCall succeeded = new ToolCall("succeeded", "write", "{\"target\":2}");
        context.requestToolExecution(succeeded);
        context.startToolExecution(succeeded);
        context.completeToolExecution(succeeded, ToolExecutionResult.success("已写入第二个目标"));
        when(review.review(any(), any())).thenReturn(
                new AnswerReviewResult(AnswerReviewResult.Action.CONTINUE, "遗漏", "再执行"),
                new AnswerReviewResult(AnswerReviewResult.Action.PASS, "如实说明各次结果", ""));

        assertTrue(hook.beforeFinalAnswer(context, request).isRewriteWithoutTools());
        assertEquals(FinalAnswerHookResult.Action.ALLOW, hook.beforeFinalAnswer(context, request).getAction());
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
