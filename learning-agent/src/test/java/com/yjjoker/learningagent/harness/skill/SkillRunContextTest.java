package com.yjjoker.learningagent.harness.skill;

import com.yjjoker.learningagent.config.HarnessSkillProperties;
import com.yjjoker.learningagent.harness.approval.AgentRunCheckpoint;
import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.context.RecoveryReferenceRegistry;
import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.skill.model.*;
import com.yjjoker.learningagent.harness.skill.service.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 验证运行范围、预算及版本恢复，不依赖真实模型是否碰巧按要求调用工具。
class SkillRunContextTest {
    private static final SkillDefinition FIRST = definition("first", "1.0.0", "第一份完整工作流");
    private static final SkillDefinition SECOND = definition("second", "1.0.0", "第二份完整工作流");

    // 没有加载时只出现索引；重复加载后正文仍只有一份，返回回执而不是重复正文。
    @Test
    void loadsOnceAndSeparatesIndexFromActiveBody() {
        SkillRunContext context = context(new HarnessSkillProperties(), FIRST, SECOND);
        try {
            context.beginRun();
            assertTrue(prompt(context).contains("first"));
            assertFalse(prompt(context).contains(FIRST.getContent()));
            var first = context.load("first", "1.0.0");
            var repeated = context.load("first", "1.0.0");
            assertTrue(first.isSuccess());
            assertTrue(new JsonMapper().readTree(repeated.getContent()).path("alreadyLoaded").asBoolean());
            assertFalse(first.getContent().contains(FIRST.getContent()));
            assertEquals(1, context.snapshot().size());
            assertEquals(1, prompt(context).split(FIRST.getContent(), -1).length - 1);
            assertFalse(prompt(context).contains(SECOND.getContent()));
        } finally {
            context.clear();
        }
    }

    // 数量与正文预算分别检查，失败不会删除已有技能或留下半份正文。
    @Test
    void preservesActiveSkillsWhenCountOrCharacterBudgetIsExceeded() {
        HarnessSkillProperties properties = new HarnessSkillProperties();
        properties.setMaxActiveSkills(1);
        SkillRunContext countLimited = context(properties, FIRST, SECOND);
        try {
            countLimited.beginRun();
            assertTrue(countLimited.load("first", "1.0.0").isSuccess());
            assertEquals("SKILL_LOAD_LIMIT_EXCEEDED", countLimited.load("second", "1.0.0").getErrorCode());
            assertTrue(countLimited.load("first", "1.0.0").isSuccess());
            assertEquals(1, countLimited.snapshot().size());
        } finally {
            countLimited.clear();
        }
        properties.setMaxActiveSkills(3);
        properties.setMaxActiveCharacters(FIRST.getContent().length());
        SkillRunContext lengthLimited = context(properties, FIRST, SECOND);
        try {
            lengthLimited.beginRun();
            assertTrue(lengthLimited.load("first", "1.0.0").isSuccess());
            assertEquals("SKILL_CONTENT_LIMIT_EXCEEDED", lengthLimited.load("second", "1.0.0").getErrorCode());
            assertFalse(prompt(lengthLimited).contains(SECOND.getContent()));
        } finally {
            lengthLimited.clear();
        }
    }

    // 旧索引版本不能激活当前版本，失败不会改变任务状态。
    @Test
    void rejectsStaleVersionWithoutActivatingSkill() {
        SkillRunContext context = context(new HarnessSkillProperties(), FIRST);
        try {
            context.beginRun();
            assertEquals("SKILL_VERSION_MISMATCH", context.load("first", "0.9.0").getErrorCode());
            assertTrue(context.snapshot().isEmpty());
        } finally {
            context.clear();
        }
    }

    // 检查点真实经过 JSON 存取，恢复内容和重复加载语义均不变化。
    @Test
    void restoresLoadedSkillsAfterCheckpointSerialization() {
        SkillRunContext context = context(new HarnessSkillProperties(), FIRST);
        try {
            context.beginRun();
            context.load("first", "1.0.0");
            AgentRunCheckpoint checkpoint = new AgentRunCheckpoint();
            checkpoint.setLoadedSkills(context.snapshot());
            JsonMapper json = new JsonMapper();
            String saved = json.writeValueAsString(checkpoint);
            assertFalse(saved.contains(FIRST.getContent()));
            context.clear();
            context.restore(json.readValue(saved, AgentRunCheckpoint.class).getLoadedSkills());
            assertTrue(prompt(context).contains(FIRST.getContent()));
            assertTrue(json.readTree(context.load("first", "1.0.0").getContent()).path("alreadyLoaded").asBoolean());
        } finally {
            context.clear();
        }
    }

    // 版本变化、忘记改版本的正文变化以及技能删除，都不能用旧审批继续运行。
    @Test
    void rejectsChangedOrRemovedSkillsBeforePublishingRestoredState() {
        SkillRunContext original = context(new HarnessSkillProperties(), FIRST);
        List<SkillCheckpointEntry> saved;
        try {
            original.beginRun();
            original.load("first", "1.0.0");
            saved = original.snapshot();
        } finally {
            original.clear();
        }
        for (SkillDefinition[] replacement : List.of(
                new SkillDefinition[]{definition("first", "2.0.0", FIRST.getContent())},
                new SkillDefinition[]{definition("first", "1.0.0", "修改后的正文")},
                new SkillDefinition[0])) {
            SkillRunContext updated = context(new HarnessSkillProperties(), replacement);
            assertEquals("SKILL_VERSION_MISMATCH", assertThrows(HarnessException.class, () -> updated.restore(saved)).getErrorCode());
            assertThrows(HarnessException.class, updated::snapshot);
        }
    }

    // 空字段、重复条目和伪造指纹不能绕过版本恢复检查。
    @Test
    void rejectsMalformedSnapshotsAndAcceptsLegacyCheckpoint() {
        SkillRunContext context = context(new HarnessSkillProperties(), FIRST);
        try {
            context.beginRun();
            context.load("first", "1.0.0");
            var entry = context.snapshot().getFirst();
            assertThrows(HarnessException.class, () -> context.restore(List.of(entry, entry)));
            assertThrows(HarnessException.class, () -> context.restore(null));
            assertThrows(HarnessException.class, () -> context.restore(List.of(new SkillCheckpointEntry("first", "1.0.0", "wrong"))));
            // 旧检查点没有 loadedSkills 字段，默认按没有激活技能处理。
            AgentRunCheckpoint legacy = new JsonMapper().readValue("{\"version\":1}", AgentRunCheckpoint.class);
            context.restore(legacy.getLoadedSkills());
            assertTrue(context.snapshot().isEmpty());
        } finally {
            context.clear();
        }
    }

    // 重启后配置收紧也不能恢复超预算正文，更不能裁掉一半后继续执行。
    @Test
    void rejectsRestoredBodyBeyondNewBudget() {
        SkillRunContext original = context(new HarnessSkillProperties(), FIRST);
        original.beginRun();
        original.load("first", "1.0.0");
        var saved = original.snapshot();
        original.clear();
        HarnessSkillProperties properties = new HarnessSkillProperties();
        properties.setMaxActiveCharacters(1);
        SkillRunContext restricted = context(properties, FIRST);
        assertThrows(HarnessException.class, () -> restricted.restore(saved));
        assertThrows(HarnessException.class, restricted::snapshot);
    }

    // 摘要只处理旧历史，系统消息里的技能正文完整保留，不进入摘要输入。
    @Test
    void keepsActiveInstructionsThroughHistorySummary() {
        SkillRunContext context = context(new HarnessSkillProperties(), FIRST);
        try {
            context.beginRun();
            context.load("first", "1.0.0");
            String system = prompt(context);
            var messages = List.of(LlmMessage.system(system), LlmMessage.user("历史".repeat(3000)), LlmMessage.user("现在的问题"));
            var compacted = new ContextManager(2000, 200).prepareForLlmRequest(messages, new RecoveryReferenceRegistry(), history -> {
                assertFalse(history.stream().anyMatch(message -> message.getContent().contains(FIRST.getContent())));
                return "历史摘要";
            }, 2);
            assertEquals(system, compacted.getFirst().getContent());
            assertTrue(compacted.get(1).isSummary());
        } finally {
            context.clear();
        }
    }

    // 同一个 Spring 单例可服务两个并发请求，技能集合不能互相串用。
    @Test
    void isolatesThreadsAndClearsState() throws Exception {
        SkillRunContext context = context(new HarnessSkillProperties(), FIRST, SECOND);
        CountDownLatch ready = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of("first", "second").stream().map(name -> executor.submit(() -> {
                context.beginRun();
                try {
                    context.load(name, "1.0.0");
                    ready.countDown();
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                    return context.snapshot().stream().map(SkillCheckpointEntry::getName).toList();
                } finally {
                    context.clear();
                    assertThrows(HarnessException.class, context::snapshot);
                }
            })).toList();
            assertEquals(List.of("first"), futures.get(0).get(10, TimeUnit.SECONDS));
            assertEquals(List.of("second"), futures.get(1).get(10, TimeUnit.SECONDS));
            assertThrows(HarnessException.class, context::snapshot);
        }
    }

    // 用真实注册表组装组件，仅用测试加载器提供固定文件内容。
    private SkillRunContext context(HarnessSkillProperties properties, SkillDefinition... definitions) {
        SkillFileLoader loader = mock(SkillFileLoader.class);
        when(loader.loadBuiltInSkills()).thenReturn(List.of(definitions));
        return new SkillRunContext(new SkillRegistry(loader), properties);
    }

    // 生成完整系统块用于断言，不能只观察加载回执就认定正文已注入。
    private String prompt(SkillRunContext context) {
        StringBuilder prompt = new StringBuilder("固定规则");
        context.appendPrompt(prompt);
        return prompt.toString();
    }

    // 使用唯一正文标识，避免索引描述与正文相同导致断言误判。
    private static SkillDefinition definition(String name, String version, String body) {
        return new SkillDefinition(new SkillIndex(name, "测试用途", version), body);
    }
}
