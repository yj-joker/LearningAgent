package com.yjjoker.learningagent.harness.skill;

import com.yjjoker.learningagent.config.HarnessSkillProperties;
import com.yjjoker.learningagent.harness.skill.model.*;
import com.yjjoker.learningagent.harness.skill.service.*;
import com.yjjoker.learningagent.harness.tool.impl.LoadSkillTool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 参数校验与激活使用真实实现，工具不会执行数据库操作或脚本。
class LoadSkillToolTest {
    private SkillRunContext context;
    private LoadSkillTool tool;

    // 建立可用技能和独立运行范围。
    @BeforeEach
    void setup() {
        SkillFileLoader loader = mock(SkillFileLoader.class);
        when(loader.loadBuiltInSkills()).thenReturn(List.of(new SkillDefinition(new SkillIndex("draft", "制定草案", "1.0.0"), "工作流程正文")));
        context = new SkillRunContext(new SkillRegistry(loader), new HarnessSkillProperties());
        context.beginRun();
        tool = new LoadSkillTool(context);
    }

    // 每个测试都释放当前线程的数据。
    @AfterEach
    void clear() { context.clear(); }

    // 工具声明与行为一致：当前任务只读、独占读取，普通历史不能重放激活回执。
    @Test
    void declaresScopedReadOnlyToolAndActivatesKnownSkill() {
        assertEquals("load_skill", tool.name());
        assertTrue(tool.isContextScopedTool());
        assertTrue(tool.requiresExclusiveBatch());
        assertFalse(tool.requiresUserApproval());
        assertFalse(tool.isMemoryWriteTool());
        assertFalse(tool.isContextRecoveryTool());
        assertEquals(List.of("name", "version"), tool.parametersSchema().get("required"));
        assertEquals(false, tool.parametersSchema().get("additionalProperties"));
        assertTrue(tool.execute("{\"name\":\"draft\",\"version\":\"1.0.0\"}").isSuccess());
        assertEquals(1, context.snapshot().size());
    }

    // 重复字段、尾随 JSON 和范围参数均不能被静默忽略。
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "[]", "{}", "null", "{\"name\":\"draft\"}",
            "{\"name\":\"draft\",\"version\":1}", "{\"name\":\"draft\",\"version\":\"\"}",
            "{\"name\":\"draft\",\"version\":\"1.0.0\",\"path\":\"file\"}",
            "{\"name\":\"draft\",\"name\":\"draft\",\"version\":\"1.0.0\"}",
            "{\"name\":\"draft\",\"version\":\"1.0.0\"} {}"})
    void rejectsMalformedInput(String input) {
        assertEquals("INVALID_TOOL_ARGUMENTS", tool.execute(input).getErrorCode());
        assertTrue(context.snapshot().isEmpty());
    }

    // 未知名称、路径或旧版本返回明确错误，不会被解释成文件访问。
    @Test
    void returnsLookupErrorsAndDoesNotActivateAnything() {
        assertEquals("SKILL_NOT_FOUND", tool.execute("{\"name\":\"missing\",\"version\":\"1\"}").getErrorCode());
        assertEquals("INVALID_ARGUMENT", tool.execute("{\"name\":\"../private\",\"version\":\"1\"}").getErrorCode());
        assertEquals("SKILL_VERSION_MISMATCH", tool.execute("{\"name\":\"draft\",\"version\":\"old\"}").getErrorCode());
        assertEquals("INVALID_TOOL_ARGUMENTS", tool.execute("x".repeat(1025)).getErrorCode());
        assertTrue(context.snapshot().isEmpty());
    }

    // 工具不能自己创建请求范围，脱离 Harness 调用时明确失败。
    @Test
    void requiresActiveHarnessScope() {
        context.clear();
        var result = tool.execute("{\"name\":\"draft\",\"version\":\"1.0.0\"}");
        assertEquals("SKILL_CONTEXT_UNAVAILABLE", result.getErrorCode());
        assertFalse(result.isRetryable());
    }
}
