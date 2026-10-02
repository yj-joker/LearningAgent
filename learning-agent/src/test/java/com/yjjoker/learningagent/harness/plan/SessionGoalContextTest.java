package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.harness.plan.service.SessionGoalContext;
import com.yjjoker.learningagent.harness.plan.model.GoalIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// 使用识别结果快照测试进度意图，不调用模型，也不写数据库。
class SessionGoalContextTest {
    private final SessionGoalContext context = new SessionGoalContext();

    // 每次测试后清理 ThreadLocal，避免测试线程复用旧用户消息。
    @AfterEach
    void clear() {
        context.clear();
    }

    // 明确的开始、完成和继续下一步属于进度变更请求。
    @Test
    void recognizesExplicitMutation() {
        context.bindIntent(new GoalIntent(false, true, false, 0.98, "明确要求完成步骤"));

        assertTrue(context.isExplicitProgressMutationRequest());
        assertFalse(context.isExplicitProgressReadOnlyRequest());
    }

    // 查询状态只读，不应被进度变更守卫拦截成写操作。
    @Test
    void separatesReadOnlyQuestion() {
        context.bindIntent(new GoalIntent(true, false, false, 0.98, "明确要求查询进度"));

        assertTrue(context.isExplicitProgressReadOnlyRequest());
        assertFalse(context.isExplicitProgressMutationRequest());
    }

    // 普通知识问题没有明确变更意图，不触发结构化动作要求。
    @Test
    void ignoresTeachingQuestion() {
        context.bindIntent(GoalIntent.unknown());

        assertFalse(context.isExplicitProgressMutationRequest());
        assertFalse(context.isExplicitProgressReadOnlyRequest());
    }
}
