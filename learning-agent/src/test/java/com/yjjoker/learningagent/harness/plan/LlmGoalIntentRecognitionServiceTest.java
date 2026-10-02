package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.config.HarnessLlmRetryProperties;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.LlmRetryExecutor;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.plan.model.GoalIntent;
import com.yjjoker.learningagent.harness.plan.service.LlmGoalIntentRecognitionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 使用假 LLM 验证意图 JSON 的解析和格式失败后的保守降级。
class LlmGoalIntentRecognitionServiceTest {

    // 合法 JSON 能转换成 Hook 可读取的三类意图。
    @Test
    void parsesStructuredIntent() {
        LlmGoalIntentRecognitionService service = serviceWith(
                "{\"progressReadOnly\":false,\"progressMutation\":false,"
                        + "\"planMutation\":true,\"confidence\":0.95,\"reason\":\"用户要求新增计划步骤\"}");

        GoalIntent intent = service.recognize("run-1", "新增一个学习步骤");

        assertTrue(intent.isPlanMutation());
        assertFalse(intent.isProgressMutation());
    }

    // 连续格式错误不把模型输出当成有效意图，而是返回全 false 的保守结果。
    @Test
    void fallsBackWhenResponseIsInvalid() {
        LlmGoalIntentRecognitionService service = serviceWith("not-json");

        GoalIntent intent = service.recognize("run-2", "请完成当前步骤");

        assertFalse(intent.isProgressReadOnly());
        assertFalse(intent.isProgressMutation());
        assertFalse(intent.isPlanMutation());
    }

    // 构造只返回固定文本的假客户端，不访问网络或数据库。
    private LlmGoalIntentRecognitionService serviceWith(String response) {
        LlmClient client = new LlmClient() {
            @Override
            public LlmResponse generate(List<LlmMessage> messages) {
                return new TextLlmResponse(response);
            }

            @Override
            public LlmResponse generateWithoutTools(List<LlmMessage> messages) {
                return new TextLlmResponse(response);
            }
        };
        HarnessLlmRetryProperties properties = new HarnessLlmRetryProperties();
        properties.setMaxAttempts(1);
        return new LlmGoalIntentRecognitionService(client, new LlmRetryExecutor(properties));
    }
}
