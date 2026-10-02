package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.harness.plan.model.GoalIntent;

// 识别用户本轮是否要求查询或修改专注目标；不执行工具，也不写数据库。
public interface GoalIntentRecognitionService {

    // 为一条用户消息生成一次意图快照，供本轮所有 Hook 复用。
    GoalIntent recognize(String runId, String userMessage);
}
