package com.yjjoker.learningagent.harness.tool;

import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("工具注册表测试")
class ToolRegistryTest {

    @Test
    @DisplayName("能够根据唯一名称找到已经注册的工具")
    void shouldFindRegisteredToolByName() {
        // 创建一个不会访问数据库或外部服务的假工具，让测试只关注注册和查找逻辑。
        Tool courseQueryTool = new FakeTool(
                "course_query",
                "根据课程编号查询课程",
                "课程名称：Java 入门"
        );
        ToolRegistry registry = new ToolRegistry(List.of(courseQueryTool));

        // 模拟未来 LLM 返回工具名称后，Harness 根据名称向注册表索要工具。
        Tool foundTool = registry.getRequiredTool("course_query");

        // assertSame 验证找到的是注册时放入的同一个 Java 对象，而不只是内容相等的新对象。
        assertSame(courseQueryTool, foundTool);
        assertEquals("课程名称：Java 入门", foundTool.execute("1001"));
        assertEquals(1, registry.getAllTools().size());
    }

    @Test
    @DisplayName("模型请求未注册工具时给出明确错误")
    void shouldRejectUnknownToolName() {
        // 空注册表代表应用当前没有提供任何可调用工具。
        ToolRegistry registry = new ToolRegistry(List.of());

        // 返回 null 会把错误推迟成难以理解的空指针，因此注册表在查找失败时立即抛出业务异常。
        LearningAgentServiceException exception = assertThrows(
                LearningAgentServiceException.class,
                () -> registry.getRequiredTool("unknown_tool")
        );

        assertEquals("模型请求了未注册的工具：unknown_tool", exception.getMessage());
    }

    @Test
    @DisplayName("拒绝注册两个同名工具")
    void shouldRejectDuplicateToolNames() {
        Tool firstTool = new FakeTool("course_query", "第一个工具", "first");
        Tool secondTool = new FakeTool("course_query", "第二个工具", "second");

        // 名称是调用工具时的唯一定位依据，所以重复名称属于应用配置错误，必须在启动注册阶段暴露。
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new ToolRegistry(List.of(firstTool, secondTool))
        );

        assertEquals("存在重复的工具名称：course_query", exception.getMessage());
    }

    // 假工具实现完整的 Tool 契约，但使用固定结果代替真实课程查询逻辑。
    private static class FakeTool implements Tool {

        private final String name;
        private final String description;
        private final String result;

        private FakeTool(String name, String description, String result) {
            this.name = name;
            this.description = description;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public String execute(String input) {
            return result;
        }
    }
}
