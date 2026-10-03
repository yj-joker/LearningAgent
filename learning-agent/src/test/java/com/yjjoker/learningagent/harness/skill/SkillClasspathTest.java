package com.yjjoker.learningagent.harness.skill;

import com.yjjoker.learningagent.harness.skill.service.SkillFileLoader;
import com.yjjoker.learningagent.harness.skill.service.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

// 走真实资源扫描和 Spring 注入，不只验证手动构造的内存定义。
class SkillClasspathTest {
    @TempDir
    Path directory;

    // 只启动技能组件，验证 Spring 能完成注入，不连接数据库或模型。
    @Test
    void registersBundledSkillThroughSpring() {
        try (var context = new AnnotationConfigApplicationContext(SkillFileLoader.class, SkillRegistry.class)) {
            SkillRegistry registry = context.getBean(SkillRegistry.class);
            assertEquals(1, registry.listSkills().size());
            var definition = registry.getRequiredSkill("learning-plan-draft");
            assertEquals("0.2.0", definition.getIndex().getVersion());
            assertTrue(definition.getContent().contains("不把草案当成已保存"));
            assertTrue(definition.getContent().contains("尚未提供正式学习计划的保存工具"));
        }
    }

    // 文件放进 JAR 后仍能扫描和读取，不能依赖资源在磁盘上有独立文件。
    @Test
    void readsSkillFromJarResources() throws Exception {
        Path archive = createJar("one.jar", "1.0.0", "JAR 内的正文");
        // 隔离类加载器，只扫描这个测试 JAR，不混入应用自带技能。
        try (var classLoader = new URLClassLoader(new URL[]{archive.toUri().toURL()}, null)) {
            SkillRegistry registry = registry(classLoader);
            assertEquals(1, registry.listSkills().size());
            assertEquals("JAR 内的正文", registry.getRequiredSkill("jar-demo").getContent());
        }
    }

    // 两个 JAR 中出现同名技能必须失败，不能由依赖加载顺序静默覆盖。
    @Test
    void rejectsDuplicateSkillsInDifferentJars() throws Exception {
        Path first = createJar("one.jar", "1.0.0", "第一份正文");
        Path second = createJar("two.jar", "2.0.0", "第二份正文");
        try (var classLoader = new URLClassLoader(new URL[]{first.toUri().toURL(), second.toUri().toURL()}, null)) {
            assertTrue(assertThrows(IllegalStateException.class, () -> registry(classLoader)).getMessage().contains("重复"));
        }
    }

    // 文件改动不会让已有注册表的索引和正文变成不同版本；重新加载后才整体更新。
    @Test
    void keepsStartupSnapshotUntilRegistryIsRebuilt() throws Exception {
        Path file = directory.resolve("skills/jar-demo/SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, document("1.0.0", "旧正文"), StandardCharsets.UTF_8);
        try (var classLoader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, null)) {
            SkillRegistry original = registry(classLoader);
            Files.writeString(file, document("2.0.0", "新正文"), StandardCharsets.UTF_8);

            assertEquals("1.0.0", original.listSkills().getFirst().getVersion());
            assertEquals("旧正文", original.getRequiredSkill("jar-demo").getContent());
            SkillRegistry refreshed = registry(classLoader);
            assertEquals("2.0.0", refreshed.listSkills().getFirst().getVersion());
            assertEquals("新正文", refreshed.getRequiredSkill("jar-demo").getContent());
        }
    }

    // 使用生产的加载器与注册表，测试只替换资源所在的类路径。
    private SkillRegistry registry(ClassLoader classLoader) {
        var resolver = new PathMatchingResourcePatternResolver(classLoader);
        // 临时 JAR 不保留连接缓存，让 Windows 在测试结束后也能释放和清理文件。
        resolver.setUseCaches(false);
        return new SkillRegistry(new SkillFileLoader(resolver));
    }

    // 创建只含技能资源的小 JAR，目录条目与 Maven 打包结果一致。
    private Path createJar(String filename, String version, String body) throws Exception {
        Path archive = directory.resolve(filename);
        try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
            for (String name : new String[]{"skills/", "skills/jar-demo/", "skills/jar-demo/SKILL.md"}) {
                output.putNextEntry(new JarEntry(name));
                if (name.endsWith("SKILL.md")) {
                    output.write(document(version, body).getBytes(StandardCharsets.UTF_8));
                }
                output.closeEntry();
            }
        }
        return archive;
    }

    // 生成合法文件内容，版本和正文由各测试控制。
    private String document(String version, String body) {
        return "---\nname: jar-demo\ndescription: JAR 资源测试\nmetadata:\n  version: \""
                + version + "\"\n---\n" + body;
    }
}
