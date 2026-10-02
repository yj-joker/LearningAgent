package com.yjjoker.learningagent.harness.plan;

import com.yjjoker.learningagent.harness.error.*;
import com.yjjoker.learningagent.harness.llm.*;
import com.yjjoker.learningagent.harness.llm.model.*;
import com.yjjoker.learningagent.harness.plan.service.FocusPlanPlanner;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import com.yjjoker.learningagent.harness.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 只使用确定性响应验证格式修复，不把这些测试冒充真实模型调用。
class FocusPlanPlannerTest {
    private static final String VALID = """
            {"goal":"理解事务","constraints":"","steps":[{"description":"解释原子性","completionCriteria":"给出转账例子"}]}
            """;
    private final LlmClient client = mock(LlmClient.class);
    private final FocusPlanPlanner planner = new FocusPlanPlanner(client, new LlmRetryExecutor(), new ToolRegistry(List.of()));

    // 合法计划只调用一次无工具模型，不会提供业务工具。
    @Test
    void acceptsShortPlanWithoutTools() {
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(VALID));
        var result = planner.createPlan("run-test", "解释事务");
        assertEquals(1, result.getSteps().size());
        assertEquals("理解事务", result.getGoal());
        verify(client).generateWithoutTools(any());
        verify(client, never()).generate(any());
    }

    // 用户明确要求两步时，模型漏掉第二步会触发一次格式修复，而不是直接保存残缺计划。
    @Test
    void repairsPlanThatDropsExplicitSecondStep() {
        String oneStep = """
                {"goal":"学习 Java 线程池","constraints":"","steps":[{"description":"理解核心线程数","completionCriteria":"能解释核心线程数"}]}
                """;
        String twoSteps = """
                {"goal":"学习 Java 线程池","constraints":"","steps":[{"description":"理解核心线程数","completionCriteria":"能解释核心线程数"},{"description":"理解最大线程数","completionCriteria":"能解释最大线程数"}]}
                """;
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(oneStep), new TextLlmResponse(twoSteps));

        var result = planner.createPlan("run-test", "我要学习线程池，分成两个步骤：第一步理解核心线程数，第二步理解最大线程数。现在先讲第一步。");

        assertEquals(2, result.getSteps().size());
        verify(client, times(2)).generateWithoutTools(any());
    }

    // 工具目录让规划器知道未来能做什么，但模型请求仍然不开放工具调用。
    @Test
    void suppliesRealCapabilitiesWithoutExecutingThem() {
        Tool tool = mock(Tool.class);
        when(tool.name()).thenReturn("session_note");
        when(tool.description()).thenReturn("保存当前会话笔记");
        when(tool.parametersSchema()).thenReturn(Map.of("type", "object", "required", List.of("content")));
        when(tool.requiresUserApproval()).thenReturn(true);
        var scoped = new FocusPlanPlanner(client, new LlmRetryExecutor(), new ToolRegistry(List.of(tool)));
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(VALID));
        scoped.createPlan("run-test", "保存笔记");
        ArgumentCaptor<List<LlmMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(client).generateWithoutTools(sent.capture());
        String prompt = sent.getValue().getFirst().getContent();
        assertTrue(prompt.contains("session_note"));
        assertTrue(prompt.contains("\"requiresUserApproval\":true"));
        assertTrue(prompt.contains("后续主 AgentLoop 可以调用"));
        verify(tool, never()).execute(any());
        verify(client, never()).generate(any());
    }

    // 首次错误时把后端原因反馈给模型，第二次成功才返回计划。
    @ParameterizedTest
    @ValueSource(strings = {
            "不是 JSON", "[]", "{}",
            "{\"goal\":12,\"steps\":[]}",
            "{\"goal\":\"理解事务\",\"steps\":[]}",
            "{\"goal\":\"理解事务\",\"steps\":[null]}",
            "{\"goal\":\"理解事务\",\"steps\":[{\"description\":\"解释\",\"completionCriteria\":false}]}",
            "{\"goal\":\"理解事务\",\"steps\":[{\"description\":\"解释\",\"completionCriteria\":\"正确\",\"status\":\"COMPLETED\"}]}",
            "{\"goal\":\"理解事务\",\"steps\":[{\"description\":\"解释\",\"completionCriteria\":\"正确\"}],\"userId\":1}",
            "{\"goal\":\"旧目标\",\"goal\":\"新目标\",\"steps\":[]}"
    })
    void repairsInvalidFormatOnce(String invalid) {
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(invalid), new TextLlmResponse(VALID));
        assertEquals("理解事务", planner.createPlan("run-test", "解释事务").getGoal());
        ArgumentCaptor<List<LlmMessage>> requests = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).generateWithoutTools(requests.capture());
        List<LlmMessage> retry = requests.getAllValues().getLast();
        assertEquals(3, retry.size());
        assertTrue(retry.getLast().getContent().contains("未通过后端校验"));
        assertEquals("解释事务", retry.get(1).getContent());
    }

    // 重复失败必须停止，不把错误文本当成计划，也不进入无限循环。
    @Test
    void stopsAfterOneRepair() {
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse("错误"));
        HarnessException error = assertThrows(HarnessException.class,
                () -> planner.createPlan("run-test", "解释事务"));
        assertFalse(error.getError().isRetryable());
        verify(client, times(2)).generateWithoutTools(any());
    }

    // 步骤上限由代码检查，不依赖模型遵守“最多三步”的提示。
    @Test
    void rejectsFourStepsAndOversizedOutput() {
        String step = "{\"description\":\"解释\",\"completionCriteria\":\"有例子\"}";
        String four = "{\"goal\":\"学习\",\"steps\":[" + String.join(",", List.of(step, step, step, step)) + "]}";
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(four), new TextLlmResponse("字".repeat(6001)));
        assertThrows(HarnessException.class, () -> planner.createPlan("run-test", "学习"));
        verify(client, times(2)).generateWithoutTools(any());
    }

    // 拒绝尾随内容和 Markdown，不从随意文本中截出一个对象。
    @ParameterizedTest
    @ValueSource(strings = {"\n{}", "\n解释文字"})
    void rejectsTrailingContent(String trailing) {
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(VALID + trailing));
        assertThrows(HarnessException.class, () -> planner.createPlan("run-test", "学习"));
    }

    // 过长字段不能绕过短计划限制。
    @Test
    void rejectsLongStepText() {
        String invalid = VALID.replace("解释原子性", "字".repeat(201));
        when(client.generateWithoutTools(any())).thenReturn(new TextLlmResponse(invalid), new TextLlmResponse(VALID));
        assertEquals(1, planner.createPlan("run-test", "学习").getSteps().size());
        verify(client, times(2)).generateWithoutTools(any());
    }

    // 即使客户端意外返回工具请求，规划阶段也不会执行，只会要求修复格式。
    @Test
    void repairsUnexpectedToolResponse() {
        when(client.generateWithoutTools(any())).thenReturn(
                new ToolCallLlmResponse(List.of(new ToolCall("x", "anything", "{}"))), new TextLlmResponse(VALID));
        assertEquals(1, planner.createPlan("run-test", "学习").getSteps().size());
        verify(client, never()).generate(any());
    }

    // 鉴权失败不是 JSON 错误，不能通过格式修复再发一次相同请求。
    @Test
    void doesNotRepairTransportOrAuthenticationFailure() {
        HarnessException failure = new HarnessException(HarnessError.of(
                HarnessErrorCode.LLM_AUTHENTICATION_FAILED, "认证失败", false, HarnessErrorSource.LLM));
        when(client.generateWithoutTools(any())).thenThrow(failure);
        assertSame(failure, assertThrows(HarnessException.class, () -> planner.createPlan("run-test", "学习")));
        verify(client).generateWithoutTools(any());
    }
}
