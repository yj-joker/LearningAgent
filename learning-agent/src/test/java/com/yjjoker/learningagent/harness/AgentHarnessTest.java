package com.yjjoker.learningagent.harness;

import com.yjjoker.learningagent.harness.impl.AgentHarnessServiceImpl;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.LlmResponse;
import com.yjjoker.learningagent.harness.llm.model.TextLlmResponse;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.llm.model.ToolCallLlmResponse;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Agent Harness 循环测试")
class AgentHarnessTest {

    @Test
    @DisplayName("模型直接返回文本时不执行工具")
    void shouldReturnDirectTextResponse() {
        // 假模型的第一次结果就是最终文本，用来模拟不需要任何外部数据的普通问答。
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new TextLlmResponse("模拟的模型回复")
        );
        RecordingTool tool = new RecordingTool("find_all_users", "张三, 李四");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(tool));
        AgentHarnessService harness = new AgentHarnessServiceImpl(fakeLlmClient, toolRegistry);

        String reply = harness.run("什么是数据库事务？");

        assertEquals("模拟的模型回复", reply);
        assertEquals(1, fakeLlmClient.receivedMessages.size());
        assertEquals("什么是数据库事务？", fakeLlmClient.receivedMessages.getFirst().getFirst().getContent());
        assertEquals(0, tool.executeCount);
    }

    @Test
    @DisplayName("模型请求工具时执行工具并把结果交回模型")
    void shouldExecuteToolAndSendResultBackToLlm() {
        ToolCall toolCall = new ToolCall("call_123", "find_all_users", "{}");

        // 第一次 generate 要求调用工具，第二次 generate 才返回基于工具数据形成的最终回答。
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                new ToolCallLlmResponse(List.of(toolCall)),
                new TextLlmResponse("目前有两位用户：张三和李四。")
        );
        RecordingTool tool = new RecordingTool("find_all_users", "张三, 李四");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(tool));
        AgentHarnessService harness = new AgentHarnessServiceImpl(fakeLlmClient, toolRegistry);

        String reply = harness.run("系统中有哪些用户？");

        assertEquals("目前有两位用户：张三和李四。", reply);
        assertEquals(1, tool.executeCount);
        assertEquals("{}", tool.receivedInput);
        assertEquals(2, fakeLlmClient.receivedMessages.size());

        // 第二次请求必须包含三部分：最初的 user 消息、assistant 工具请求、tool 工具结果。
        List<LlmMessage> secondRequest = fakeLlmClient.receivedMessages.get(1);
        assertEquals(3, secondRequest.size());
        assertEquals("user", secondRequest.get(0).getRole());
        assertEquals("assistant", secondRequest.get(1).getRole());
        assertEquals("call_123", secondRequest.get(1).getToolCalls().getFirst().id());
        assertEquals("tool", secondRequest.get(2).getRole());
        assertEquals("call_123", secondRequest.get(2).getToolCallId());
        assertEquals("张三, 李四", secondRequest.get(2).getContent());
    }

    @Test
    @DisplayName("模型持续请求工具时在安全上限处停止")
    void shouldStopAfterMaximumToolRounds() {
        ToolCallLlmResponse repeatedToolCall = new ToolCallLlmResponse(
                List.of(new ToolCall("call_repeat", "find_all_users", "{}"))
        );

        // 准备六次相同工具请求：前五轮允许执行，第六轮会触发 Harness 的循环保护。
        FakeLlmClient fakeLlmClient = new FakeLlmClient(
                repeatedToolCall,
                repeatedToolCall,
                repeatedToolCall,
                repeatedToolCall,
                repeatedToolCall,
                repeatedToolCall
        );
        RecordingTool tool = new RecordingTool("find_all_users", "张三");
        AgentHarnessService harness = new AgentHarnessServiceImpl(
                fakeLlmClient,
                new ToolRegistry(List.of(tool))
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> harness.run("一直查用户")
        );

        assertEquals("Harness 超过最多 5 轮工具调用，已停止继续执行", exception.getMessage());
        assertEquals(5, tool.executeCount);
    }

    // 假模型按顺序返回预先准备好的结果，并保存每次收到的完整消息列表。
    // 它不会访问网络，因此测试可以只验证 Harness 是否正确控制循环。
    private static class FakeLlmClient implements LlmClient {

        private final Deque<LlmResponse> responses;
        private final List<List<LlmMessage>> receivedMessages = new ArrayList<>();

        private FakeLlmClient(LlmResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public LlmResponse generate(List<LlmMessage> messages) {
            // List.copyOf 保存本轮请求的只读快照，防止 Harness 后续追加消息改变已经记录的历史。
            receivedMessages.add(List.copyOf(messages));

            if (responses.isEmpty()) {
                throw new IllegalStateException("测试没有准备足够的模型响应");
            }
            return responses.removeFirst();
        }
    }

    // 这个假工具记录执行次数和输入参数，并返回固定数据，便于验证 Harness 是否真的调用了它。
    private static class RecordingTool implements Tool {

        private final String name;
        private final String result;
        private int executeCount;
        private String receivedInput;

        private RecordingTool(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public String execute(String input) {
            executeCount++;
            receivedInput = input;
            return result;
        }
    }
}
