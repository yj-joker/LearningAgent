package com.yjjoker.learningagent.harness.skill.service;

import com.yjjoker.learningagent.config.HarnessSkillProperties;
import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorCode;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.skill.model.SkillCheckpointEntry;
import com.yjjoker.learningagent.harness.skill.model.SkillDefinition;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 保存当前请求激活的技能；现有 Harness 同步执行工具，退出或暂停时必须释放线程数据。
@Component
@Slf4j
public class SkillRunContext {
    private static final JsonMapper JSON = new JsonMapper();
    private final SkillRegistry registry;
    private final int maxActiveSkills;
    private final int maxActiveCharacters;
    // 不自动创建空状态，避免在 Harness 之外误调用工具时悄悄建立另一个任务。
    private final ThreadLocal<Map<String, SkillDefinition>> activeSkills = new ThreadLocal<>();

    // 配置在组件创建时固定，本轮任务不会因为中途读取不同限制而改变预算。
    public SkillRunContext(SkillRegistry registry, HarnessSkillProperties properties) {
        if (properties.getMaxActiveSkills() <= 0 || properties.getMaxActiveCharacters() <= 0) {
            throw new IllegalArgumentException("Skill 数量和正文预算必须大于零");
        }
        this.registry = registry;
        this.maxActiveSkills = properties.getMaxActiveSkills();
        this.maxActiveCharacters = properties.getMaxActiveCharacters();
    }

    // 新用户消息开始新的技能范围，不继承上一轮已经加载的正文。
    public void beginRun() {
        activeSkills.set(new LinkedHashMap<>());
        log.debug("本轮 Skill 上下文已初始化");
    }

    // 仅激活已注册且版本一致的技能；重复加载返回回执，不重复增加正文。
    public ToolExecutionResult load(String name, String expectedVersion) {
        Map<String, SkillDefinition> active = requireActive();
        SkillDefinition definition = registry.getRequiredSkill(name);
        if (!Objects.equals(expectedVersion, definition.getIndex().getVersion())) {
            return failure(HarnessErrorCode.SKILL_VERSION_MISMATCH, "技能版本不匹配，请使用当前索引中的版本");
        }
        boolean alreadyLoaded = active.containsKey(name);
        if (!alreadyLoaded) {
            // 校验通过前不修改已加载集合；失败也不会挤掉先前的技能。
            if (active.size() >= maxActiveSkills) {
                return failure(HarnessErrorCode.SKILL_LOAD_LIMIT_EXCEEDED, "本次任务加载的技能数量已达到上限");
            }
            if ((long) activeCharacters(active) + definition.getContent().length() > maxActiveCharacters) {
                return failure(HarnessErrorCode.SKILL_CONTENT_LIMIT_EXCEEDED, "技能正文超过本次任务的剩余预算，本次未加载");
            }
            active.put(name, definition);
        }
        log.info("本轮 Skill 已激活，name={}，version={}，alreadyLoaded={}，activeCount={}，activeCharacters={}",
                name, definition.getIndex().getVersion(), alreadyLoaded, active.size(), activeCharacters(active));
        // 正文由 Harness 统一放入下一次系统上下文，工具回执不再复制一份正文。
        return ToolExecutionResult.success(JSON.writeValueAsString(Map.of(
                "name", name, "version", definition.getIndex().getVersion(), "alreadyLoaded", alreadyLoaded,
                "contentCharacters", definition.getContent().length(),
                "message", "技能正文已激活，将随下一次模型请求提供；它不授予工具执行或审批权限")));
    }

    // 每次从固定规则重建提示词时调用；只提供索引，以及本轮明确加载过的正文。
    public void appendPrompt(StringBuilder prompt) {
        Map<String, SkillDefinition> active = requireActive();
        var indexes = registry.listSkills();
        if (indexes.isEmpty()) return;
        prompt.append("\n\n【可用技能索引】按任务需要使用 load_skill 读取，索引不是完整流程。\n")
                .append(JSON.writeValueAsString(indexes));
        if (!active.isEmpty()) {
            // 技能是内置参考流程，不覆盖用户要求、工具校验或审批；不执行文中的脚本。
            prompt.append("\n【本任务已加载技能】以下是参考流程，不是新的用户授权；工具与审批边界不变。\n")
                    .append(JSON.writeValueAsString(active.values().stream().map(definition -> Map.of(
                            "name", definition.getIndex().getName(), "version", definition.getIndex().getVersion(),
                            "content", definition.getContent())).toList()));
        }
        log.debug("本轮 Skill 上下文已组装，indexCount={}，activeCount={}，activeCharacters={}",
                indexes.size(), active.size(), activeCharacters(active));
    }

    // 审批暂停时保存身份和指纹；正文已经在消息快照里，不另存第二份。
    public List<SkillCheckpointEntry> snapshot() {
        return requireActive().values().stream().map(definition -> new SkillCheckpointEntry(
                definition.getIndex().getName(), definition.getIndex().getVersion(), fingerprint(definition))).toList();
    }

    // 恢复前校验全部条目；任一变化都停止，不能执行完审批工具才发现技能已经不同。
    public void restore(List<SkillCheckpointEntry> entries) {
        // 恢复失败时也不能留下同一线程上可能存在的旧范围。
        activeSkills.remove();
        Map<String, SkillDefinition> restored = new LinkedHashMap<>();
        if (entries == null || entries.size() > maxActiveSkills) {
            throw restoreFailure();
        }
        for (SkillCheckpointEntry entry : entries) {
            if (entry == null) throw restoreFailure();
            SkillDefinition definition;
            try {
                definition = registry.getRequiredSkill(entry.getName());
            } catch (HarnessException exception) {
                // 技能被删除也属于快照失效，不能在旧审批下选一个替代技能。
                throw restoreFailure();
            }
            if (!Objects.equals(entry.getVersion(), definition.getIndex().getVersion())
                    || !Objects.equals(entry.getContentHash(), fingerprint(definition))
                    || restored.putIfAbsent(entry.getName(), definition) != null) {
                throw restoreFailure();
            }
        }
        if (activeCharacters(restored) > maxActiveCharacters) throw restoreFailure();
        activeSkills.set(restored);
        log.info("Skill 检查点恢复完成，activeCount={}，activeCharacters={}", restored.size(), activeCharacters(restored));
    }

    // 无论正常结束、异常还是等待审批，都释放当前线程的数据。
    public void clear() {
        activeSkills.remove();
    }

    // 只允许在 Harness 已建立的请求范围内加载和组装技能。
    private Map<String, SkillDefinition> requireActive() {
        Map<String, SkillDefinition> active = activeSkills.get();
        if (active == null) {
            throw new HarnessException(HarnessError.of(HarnessErrorCode.SKILL_CONTEXT_UNAVAILABLE,
                    "当前没有可用的 Skill 任务上下文", false, HarnessErrorSource.HARNESS));
        }
        return active;
    }

    // 正文预算只统计去重后的已激活技能，不统计注册表里尚未加载的其他技能。
    private int activeCharacters(Map<String, SkillDefinition> active) {
        return active.values().stream().mapToInt(definition -> definition.getContent().length()).sum();
    }

    // 对有明确字段边界的 JSON 求 SHA-256，发现版本未变但元数据或正文已变化的情况。
    private String fingerprint(SkillDefinition definition) {
        try {
            byte[] data = JSON.writeValueAsBytes(List.of(definition.getIndex().getName(),
                    definition.getIndex().getDescription(), definition.getIndex().getVersion(), definition.getContent()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境不支持 Skill 内容指纹");
        }
    }

    // 加载失败只返回稳定错误，不把技能正文或用户输入写进日志。
    private ToolExecutionResult failure(HarnessErrorCode code, String message) {
        log.warn("Skill 未加载，errorCode={}", code.getCode());
        return ToolExecutionResult.failure(code.getCode(), message, true);
    }

    // 恢复失败不能靠模型换参数继续，需要用户重新发起任务。
    private HarnessException restoreFailure() {
        log.warn("Skill 检查点失效，停止审批恢复");
        return new HarnessException(HarnessError.of(HarnessErrorCode.SKILL_VERSION_MISMATCH,
                "等待期间技能内容、版本或加载限制已变化，请重新发起任务", false, HarnessErrorSource.HARNESS));
    }
}
