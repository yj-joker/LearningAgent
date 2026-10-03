package com.yjjoker.learningagent.harness.skill;

import com.yjjoker.learningagent.harness.error.HarnessErrorCode;
import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.skill.model.SkillDefinition;
import com.yjjoker.learningagent.harness.skill.model.SkillIndex;
import com.yjjoker.learningagent.harness.skill.service.SkillFileLoader;
import com.yjjoker.learningagent.harness.skill.service.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 注册与查询只使用已校验的定义，验证索引不会意外带上完整正文。
class SkillRegistryTest {
    private final SkillFileLoader loader = mock(SkillFileLoader.class);

    // 索引稳定排序且只包含三个字段；完整正文必须按名称另行读取。
    @Test
    void separatesIndexFromBodyAndKeepsStableOrder() {
        SkillDefinition second = definition("zebra", "第二份正文");
        SkillDefinition first = definition("alpha", "第一份正文");
        when(loader.loadBuiltInSkills()).thenReturn(List.of(second, first));
        SkillRegistry registry = new SkillRegistry(loader);

        assertEquals(List.of("alpha", "zebra"), registry.listSkills().stream().map(SkillIndex::getName).toList());
        var json = new JsonMapper().valueToTree(registry.listSkills());
        assertEquals(3, json.get(0).size());
        assertTrue(json.get(0).has("name"));
        assertTrue(json.get(0).has("description"));
        assertTrue(json.get(0).has("version"));
        assertFalse(json.toString().contains("正文"));
        assertSame(first, registry.getRequiredSkill("alpha"));
        assertEquals("第一份正文", registry.getRequiredSkill("alpha").getContent());
        // 读取索引或正文都不重新加载文件，调用方也不能修改索引集合。
        verify(loader).loadBuiltInSkills();
        assertThrows(UnsupportedOperationException.class, () -> registry.listSkills().clear());
    }

    // 同名定义不能靠扫描顺序决定谁生效，即使它们声明不同版本也必须失败。
    @Test
    void rejectsDuplicateNamesAcrossVersions() {
        when(loader.loadBuiltInSkills()).thenReturn(List.of(definition("same", "第一份正文"),
                new SkillDefinition(new SkillIndex("same", "用途", "2.0.0"), "第二份正文")));
        assertEquals("存在重复的 Skill 名称：same", assertThrows(IllegalStateException.class,
                () -> new SkillRegistry(loader)).getMessage());
    }

    // 文件校验失败不能得到部分注册表，也不能把异常当成空目录。
    @Test
    void stopsRegistrationWhenLoadingFails() {
        when(loader.loadBuiltInSkills()).thenThrow(new IllegalStateException("技能配置错误"));
        assertThrows(IllegalStateException.class, () -> new SkillRegistry(loader));
    }

    // 不存在的合法名称返回统一错误码，不返回 null，也不搜索其他文件。
    @Test
    void rejectsUnknownSkillWithStableErrorCode() {
        when(loader.loadBuiltInSkills()).thenReturn(List.of());
        SkillRegistry registry = new SkillRegistry(loader);
        assertTrue(registry.listSkills().isEmpty());
        HarnessException error = assertThrows(HarnessException.class, () -> registry.getRequiredSkill("missing"));
        assertEquals(HarnessErrorCode.SKILL_NOT_FOUND.getCode(), error.getErrorCode());
        verify(loader).loadBuiltInSkills();
    }

    // 拒绝路径与 URL 输入；这些参数永远不会交给资源加载器解释。
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "../private", "skills/alpha/SKILL.md", "C:\\private\\data", "https://example.com", "Alpha", " alpha"})
    void rejectsPathsAndInvalidNames(String input) {
        when(loader.loadBuiltInSkills()).thenReturn(List.of(definition("alpha", "正文")));
        SkillRegistry registry = new SkillRegistry(loader);
        HarnessException error = assertThrows(HarnessException.class, () -> registry.getRequiredSkill(input));
        assertEquals(HarnessErrorCode.INVALID_ARGUMENT.getCode(), error.getErrorCode());
        verify(loader).loadBuiltInSkills();
    }

    // 构造不可变的测试定义，不访问数据库、文件或模型。
    private SkillDefinition definition(String name, String body) {
        return new SkillDefinition(new SkillIndex(name, "帮助理解知识", "1.0.0"), body);
    }
}
