package com.yjjoker.learningagent.harness.skill;

import com.yjjoker.learningagent.harness.skill.service.SkillFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 文件校验只使用内存资源，不启动数据库、不请求模型。
class SkillFileLoaderTest {
    private static final String PATTERN = "classpath*:skills/*/SKILL.md";
    private static final String HEADER = """
            ---
            name: draft-plan
            description: 帮助制定学习草案
            metadata:
              version: "1.0.0"
            ---
            """;
    private static final String VALID = HEADER + "# 工作方法\n先了解目标，再形成草案。\n";
    private final ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
    private final SkillFileLoader loader = new SkillFileLoader(resolver);

    // 元数据和正文分开读取，正文内的分隔线不能被误当成另一个文件头。
    @Test
    void loadsMetadataAndCompleteMarkdown() throws Exception {
        Resource resource = resource("draft-plan", VALID + "\n---\n补充说明");
        when(resolver.getResources(PATTERN)).thenReturn(new Resource[]{resource});

        var skill = loader.loadBuiltInSkills().getFirst();

        assertEquals("draft-plan", skill.getIndex().getName());
        assertEquals("帮助制定学习草案", skill.getIndex().getDescription());
        assertEquals("1.0.0", skill.getIndex().getVersion());
        assertEquals("# 工作方法\n先了解目标，再形成草案。\n\n---\n补充说明", skill.getContent());
        // 扫描路径由服务端固定，不从元数据拼接新的文件路径。
        verify(resolver).getResources(PATTERN);
    }

    // 兼容 Windows 换行、UTF-8 BOM，以及 YAML 的多行用途说明。
    @Test
    void acceptsBomCrLfAndMultilineDescription() throws Exception {
        String document = VALID.replace("description: 帮助制定学习草案", "description: |\n  明确学习目标\n  制定草案")
                .replace("\n", "\r\n");
        Resource file = resource("draft-plan", "\uFEFF" + document);
        when(resolver.getResources(PATTERN)).thenReturn(new Resource[]{file});

        var skill = loader.loadBuiltInSkills().getFirst();

        assertEquals("明确学习目标\n制定草案", skill.getIndex().getDescription());
        assertTrue(skill.getContent().contains("先了解目标，再形成草案。"));
    }

    // 必填字段、错误类型、重复键及未支持选项都必须在启动时明确失败。
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void rejectsMalformedDocuments(String scenario, String document, String reason) throws Exception {
        Resource file = resource("draft-plan", document);
        when(resolver.getResources(PATTERN)).thenReturn(new Resource[]{file});

        IllegalStateException error = assertThrows(IllegalStateException.class, loader::loadBuiltInSkills);

        assertTrue(error.getMessage().contains(reason), error.getMessage());
    }

    // 每个样本只改变一处，便于定位究竟是哪条文件约定被破坏。
    private static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                Arguments.of("缺少文件头", "# 只有正文", "YAML 元数据"),
                Arguments.of("缺少结束分隔线", VALID.replace("---\n#", "#"), "YAML 元数据"),
                Arguments.of("缺少名称", VALID.replace("name: draft-plan\n", ""), "name 必须"),
                Arguments.of("缺少用途", VALID.replace("description: 帮助制定学习草案\n", ""), "description 必须"),
                Arguments.of("用途为空", VALID.replace("帮助制定学习草案", "''"), "description 必须"),
                Arguments.of("用途不是字符串", VALID.replace("帮助制定学习草案", "true"), "description 必须"),
                Arguments.of("用途过长", VALID.replace("帮助制定学习草案", "字".repeat(1025)), "1024"),
                Arguments.of("缺少元数据", VALID.replace("metadata:\n  version: \"1.0.0\"\n", ""), "metadata"),
                Arguments.of("缺少版本", VALID.replace("version:", "author:"), "version 必须"),
                Arguments.of("版本不是字符串", VALID.replace("\"1.0.0\"", "1.0"), "字符串"),
                Arguments.of("版本为空", VALID.replace("\"1.0.0\"", "\"\""), "version 必须"),
                Arguments.of("版本含空白", VALID.replace("\"1.0.0\"", "\"version 1\""), "版本标识"),
                Arguments.of("版本过长", VALID.replace("1.0.0", "a".repeat(65)), "版本标识"),
                Arguments.of("名称重复", VALID.replace("name: draft-plan", "name: draft-plan\nname: other"), "YAML 格式错误"),
                Arguments.of("版本重复", VALID.replace("version: \"1.0.0\"", "version: \"1.0.0\"\n  version: \"2.0.0\""), "YAML 格式错误"),
                Arguments.of("未知字段", VALID.replace("metadata:", "unexpected: value\nmetadata:"), "仅支持"),
                Arguments.of("不支持脚本配置", VALID.replace("metadata:", "scripts: run.sh\nmetadata:"), "仅支持"),
                Arguments.of("错误 YAML", VALID.replace("name: draft-plan", "name: ["), "YAML 格式错误"),
                Arguments.of("不接受自定义 YAML 标签", VALID.replace("name: draft-plan", "name: !unsupported value"), "YAML 格式错误"),
                Arguments.of("顶层不是对象", "---\n- first\n- second\n---\n正文", "必须是一个对象"),
                Arguments.of("集合别名", VALID.replace("metadata:", "alias: &values [one, two]\ncopy: *values\nmetadata:"), "YAML 格式错误"),
                Arguments.of("正文为空", HEADER + " \n\t", "正文不能为空"),
                Arguments.of("目录名不匹配", VALID.replace("name: draft-plan", "name: other-plan"), "目录名称一致")
        );
    }

    // 名称必须能作为稳定注册键，不能混入路径、空格或不符合约定的连接符。
    @ParameterizedTest
    @ValueSource(strings = {"Draft-plan", "-draft", "draft-", "draft--plan", "draft_plan", "../draft", "draft plan"})
    void rejectsInvalidNames(String name) throws Exception {
        Resource file = resource("draft-plan", VALID.replace("name: draft-plan", "name: " + name));
        when(resolver.getResources(PATTERN)).thenReturn(new Resource[]{file});
        assertThrows(IllegalStateException.class, loader::loadBuiltInSkills);
    }

    // 上限以内完整读取，多一个字节则拒绝；不把半份技能当作合法正文。
    @Test
    void enforcesExactFileByteLimitAndClosesStream() throws Exception {
        int limit = 64 * 1024;
        String exact = HEADER + "x".repeat(limit - HEADER.getBytes(StandardCharsets.UTF_8).length);
        Resource bounded = resource("draft-plan", exact);
        when(resolver.getResources(PATTERN)).thenReturn(new Resource[]{bounded});
        assertEquals(exact.substring(HEADER.length()), loader.loadBuiltInSkills().getFirst().getContent());

        Resource oversized = resource("draft-plan", exact + "x");
        InputStream stream = spy(new ByteArrayInputStream((exact + "x").getBytes(StandardCharsets.UTF_8)));
        when(oversized.getInputStream()).thenReturn(stream);
        when(resolver.getResources(PATTERN)).thenReturn(new Resource[]{oversized});
        assertTrue(assertThrows(IllegalStateException.class, loader::loadBuiltInSkills).getMessage().contains("字节上限"));
        verify(stream).close();
    }

    // 非 UTF-8 数据不能被静默替换成乱码，否则规则含义可能变化。
    @Test
    void rejectsMalformedUtf8() throws Exception {
        Resource invalid = resource("draft-plan", VALID);
        when(invalid.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{(byte) 0xC3, 0x28}));
        when(resolver.getResources(PATTERN)).thenReturn(new Resource[]{invalid});
        assertTrue(assertThrows(IllegalStateException.class, loader::loadBuiltInSkills).getMessage().contains("UTF-8"));
    }

    // 资源读取故障与文件内容错误分别报出，不能静默漏掉一个技能。
    @Test
    void reportsReadAndScanFailuresWithoutLeakingContents() throws Exception {
        Resource unreadable = resource("draft-plan", VALID);
        when(unreadable.getInputStream()).thenThrow(new IOException("不应展示的底层文本"));
        when(resolver.getResources(PATTERN)).thenReturn(new Resource[]{unreadable});
        IllegalStateException readError = assertThrows(IllegalStateException.class, loader::loadBuiltInSkills);
        assertTrue(readError.getMessage().contains("无法读取文件"));
        assertFalse(readError.getMessage().contains("不应展示"));

        when(resolver.getResources(PATTERN)).thenThrow(new IOException("不应展示的底层文本"));
        assertEquals("无法扫描内置 Skill 资源目录", assertThrows(IllegalStateException.class, loader::loadBuiltInSkills).getMessage());
    }

    // 文件过多直接失败；没有文件则允许空注册表，不虚构默认技能。
    @Test
    void supportsEmptyDirectoryAndRejectsExcessiveResources() throws Exception {
        when(resolver.getResources(PATTERN)).thenReturn(new Resource[0]);
        assertTrue(loader.loadBuiltInSkills().isEmpty());
        when(resolver.getResources(PATTERN)).thenReturn(new Resource[65]);
        assertTrue(assertThrows(IllegalStateException.class, loader::loadBuiltInSkills).getMessage().contains("数量超过上限"));
    }

    // 模拟扫描到的文件位置，每次打开都返回独立的内存输入流。
    private Resource resource(String folder, String content) throws Exception {
        Resource resource = mock(Resource.class);
        when(resource.getURL()).thenReturn(URI.create("file:/builtin/skills/" + folder + "/SKILL.md").toURL());
        when(resource.getInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return resource;
    }
}
