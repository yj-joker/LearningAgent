package com.yjjoker.learningagent.harness.skill.service;

import com.yjjoker.learningagent.harness.skill.model.SkillDefinition;
import com.yjjoker.learningagent.harness.skill.model.SkillIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 只加载随应用发布的内置文件，不接收来自用户或模型的目录、URL 或脚本。
@Component
@Slf4j
public class SkillFileLoader {
    static final String RESOURCE_PATTERN = "classpath*:skills/*/SKILL.md";
    static final int MAX_FILE_BYTES = 64 * 1024;
    static final int MAX_SKILLS = 64;
    // 第一阶段只支持这个明确的字段子集；遇到未实现的选项时不静默忽略。
    private static final Set<String> FIELDS = Set.of("name", "description", "metadata");
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\R(.*?)\\R---(?:\\R|\\z)(.*)\\z", Pattern.DOTALL);
    private final ResourcePatternResolver resolver;

    // Spring 提供资源加载器；通过流读取，开发目录和打包后的 JAR 都能使用。
    public SkillFileLoader(ResourceLoader resourceLoader) {
        this.resolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
    }

    // 启动时扫描固定目录；任一文件错误都停止注册，不留下只加载一半的技能列表。
    public List<SkillDefinition> loadBuiltInSkills() {
        Resource[] resources;
        try {
            resources = resolver.getResources(RESOURCE_PATTERN);
        } catch (IOException exception) {
            log.error("内置 Skill 扫描失败，errorType={}", exception.getClass().getSimpleName());
            throw new IllegalStateException("无法扫描内置 Skill 资源目录");
        }
        if (resources.length > MAX_SKILLS) {
            log.error("内置 Skill 数量超过上限，skillCount={}，maxSkills={}", resources.length, MAX_SKILLS);
            throw new IllegalStateException("内置 Skill 数量超过上限：" + MAX_SKILLS);
        }

        List<SkillDefinition> definitions = new ArrayList<>();
        for (Resource resource : resources) {
            // 文件只在启动时读取，后续按名称查注册表，不再按调用参数访问磁盘。
            definitions.add(load(resource));
        }
        if (definitions.isEmpty()) {
            log.warn("未发现内置 Skill，注册表将保持为空");
        }
        return List.copyOf(definitions);
    }

    // 分开解析 YAML 元数据和 Markdown 正文，并验证名称与所在目录一致。
    private SkillDefinition load(Resource resource) {
        String folder = folderName(resource);
        String source = folder + "/SKILL.md";
        String document = readUtf8(resource, source);
        Matcher matcher = FRONT_MATTER.matcher(document);
        if (!matcher.matches()) {
            throw invalid(source, "文件必须以 --- 包围的 YAML 元数据开头");
        }

        Map<?, ?> fields = parseMetadata(matcher.group(1), source);
        if (fields.keySet().stream().anyMatch(key -> !(key instanceof String) || !FIELDS.contains(key))) {
            throw invalid(source, "当前仅支持 name、description、metadata 字段");
        }
        if (!(fields.get("metadata") instanceof Map<?, ?> metadata)) {
            throw invalid(source, "缺少 metadata 对象和 metadata.version");
        }
        // metadata 采用字符串键值；避免 YAML 将未加引号的版本数字转换成其他类型。
        if (metadata.entrySet().stream().anyMatch(entry -> !(entry.getKey() instanceof String)
                || !(entry.getValue() instanceof String))) {
            throw invalid(source, "metadata 的键和值必须是字符串，版本请写成带引号的文本");
        }

        SkillDefinition definition;
        try {
            SkillIndex index = new SkillIndex(requiredText(fields, "name", source),
                    requiredText(fields, "description", source), requiredText(metadata, "version", source));
            if (!folder.equals(index.getName())) {
                throw invalid(source, "name 必须与所在目录名称一致");
            }
            definition = new SkillDefinition(index, matcher.group(2));
        } catch (IllegalArgumentException exception) {
            // 只展示字段规则，不输出 YAML 原文或技能正文。
            throw invalid(source, exception.getMessage());
        }
        log.info("内置 Skill 文件校验通过，name={}，version={}，contentCharacters={}",
                definition.getIndex().getName(), definition.getIndex().getVersion(), definition.getContent().length());
        return definition;
    }

    // 限制读取字节数，并拒绝错误编码，防止用被截断或乱码的规则继续运行。
    private String readUtf8(Resource resource, String source) {
        try (InputStream stream = resource.getInputStream()) {
            // 多读一个字节判断是否超限，不能悄悄截断技能说明。
            byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw invalid(source, "文件超过 " + MAX_FILE_BYTES + " 字节上限");
            }
            String document = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            // 兼容编辑器生成的 UTF-8 BOM，不把它误认为正文的一部分。
            return document.startsWith("\uFEFF") ? document.substring(1) : document;
        } catch (CharacterCodingException exception) {
            throw invalid(source, "文件必须使用有效的 UTF-8 编码");
        } catch (IOException exception) {
            throw invalid(source, "无法读取文件，错误类型：" + exception.getClass().getSimpleName());
        }
    }

    // 仅解析普通 YAML 数据，禁用重复键和集合别名，不实例化文件指定的 Java 类型。
    private Map<?, ?> parseMetadata(String text, String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(MAX_FILE_BYTES);
        try {
            Object value = new Yaml(new SafeConstructor(options)).load(text);
            if (!(value instanceof Map<?, ?> fields)) {
                throw invalid(source, "YAML 元数据必须是一个对象");
            }
            return fields;
        } catch (YAMLException exception) {
            // YAML 异常可能包含原文，日志和启动异常都只记录类型及文件名称。
            throw invalid(source, "YAML 格式错误，错误类型：" + exception.getClass().getSimpleName());
        }
    }

    // 从固定扫描结果中取得直接父目录；不使用 getFile，避免 JAR 内资源读取失败。
    private String folderName(Resource resource) {
        try {
            String path = resource.getURL().getPath();
            if (!path.endsWith("/SKILL.md")) {
                throw invalid("内置资源", "技能入口必须命名为 SKILL.md");
            }
            String parent = path.substring(0, path.length() - "/SKILL.md".length());
            String folder = parent.substring(parent.lastIndexOf('/') + 1);
            if (!SkillIndex.isValidName(folder)) {
                throw invalid("内置资源", "技能目录名称不合法");
            }
            return folder;
        } catch (IOException exception) {
            throw invalid("内置资源", "无法定位技能目录");
        }
    }

    // 必填字段必须是非空字符串，不能把列表、数字或布尔值直接转换成提示词。
    private String requiredText(Map<?, ?> fields, String key, String source) {
        if (!(fields.get(key) instanceof String value) || value.isBlank()) {
            throw invalid(source, key + " 必须是非空字符串");
        }
        return value;
    }

    // 配置错误在启动时暴露，输出定位信息和原因，不记录完整技能内容。
    private IllegalStateException invalid(String source, String reason) {
        log.error("内置 Skill 配置错误，source={}，reason={}", source, reason);
        return new IllegalStateException("内置 Skill 配置错误：" + source + "；" + reason);
    }
}
