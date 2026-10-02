package com.yjjoker.learningagent.harness.review;

import com.yjjoker.learningagent.harness.hook.AgentRunContext;

// 审查回答与执行事实是否一致；不执行工具，不写业务数据库。
public interface AnswerReviewService {
    // 结合用户请求、对话、实际工具结果和当前进度，给出结构化处理意见。
    AnswerReviewResult review(AgentRunContext context, AnswerReviewRequest request);
}
