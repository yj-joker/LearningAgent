package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.harness.plan.service.SessionGoalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// 使用真实关键词边界测试进度意图，不调用模型，也不写数据库。
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
        context.bindUserMessage("请完成第一步，然后进入下一步");

        assertTrue(context.isExplicitProgressMutationRequest());
        assertFalse(context.isExplicitProgressReadOnlyRequest());
    }

    // 查询状态只读，不应被进度变更守卫拦截成写操作。
    @Test
    void separatesReadOnlyQuestion() {
        context.bindUserMessage("请查看当前进度和步骤状态");

        assertTrue(context.isExplicitProgressReadOnlyRequest());
        assertFalse(context.isExplicitProgressMutationRequest());
    }

    // 普通知识问题没有明确变更意图，不触发结构化动作要求。
    @Test
    void ignoresTeachingQuestion() {
        context.bindUserMessage("核心线程数是什么意思？");

        assertFalse(context.isExplicitProgressMutationRequest());
        assertFalse(context.isExplicitProgressReadOnlyRequest());
    }
}
