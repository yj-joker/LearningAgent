package com.yjjoker.learningagent.harness.skill.service;

import com.yjjoker.learningagent.harness.error.HarnessError;
import com.yjjoker.learningagent.harness.error.HarnessErrorCode;
import com.yjjoker.learningagent.harness.error.HarnessErrorSource;
import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.skill.model.SkillDefinition;
import com.yjjoker.learningagent.harness.skill.model.SkillIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 注册表只提供索引和按名称读取；模型上下文由 SkillRunContext 组装，不改变工具权限。
@Component
@Slf4j
public class SkillRegistry {
    private final Map<String, SkillDefinition> skillsByName;
    private final List<SkillIndex> indexes;

    // Spring 启动时调用一次；全部文件通过校验后才发布只读注册表。
    public SkillRegistry(SkillFileLoader loader) {
        Map<String, SkillDefinition> registered = new LinkedHashMap<>();
        // 按名称排序，让目录扫描顺序变化时，索引仍保持稳定。
        List<SkillDefinition> definitions = loader.loadBuiltInSkills().stream()
                .sorted(Comparator.comparing(definition -> definition.getIndex().getName())).toList();
        for (SkillDefinition definition : definitions) {
            String name = definition.getIndex().getName();
            if (registered.putIfAbsent(name, definition) != null) {
                log.error("内置 Skill 名称重复，name={}", name);
                throw new IllegalStateException("存在重复的 Skill 名称：" + name);
            }
        }
        this.skillsByName = Collections.unmodifiableMap(registered);
        this.indexes = definitions.stream().map(SkillDefinition::getIndex).toList();
        log.info("内置 Skill 注册完成，skillCount={}", indexes.size());
    }

    // 只返回轻量索引；调用方不能从索引对象访问正文或修改注册内容。
    public List<SkillIndex> listSkills() {
        log.debug("读取 Skill 索引，skillCount={}", indexes.size());
        return indexes;
    }

    // 只按注册名查启动快照，不把输入拼接成文件路径，也不在找不到时尝试网络地址。
    public SkillDefinition getRequiredSkill(String name) {
        if (!SkillIndex.isValidName(name)) {
            log.warn("读取 Skill 被拒绝，reason=INVALID_NAME");
            throw new HarnessException(HarnessError.of(HarnessErrorCode.INVALID_ARGUMENT,
                    "Skill 名称不合法，请使用技能索引中的名称", true, HarnessErrorSource.HARNESS));
        }
        SkillDefinition definition = skillsByName.get(name);
        if (definition == null) {
            log.warn("读取 Skill 失败，name={}，reason=NOT_REGISTERED", name);
            throw new HarnessException(HarnessError.of(HarnessErrorCode.SKILL_NOT_FOUND,
                    "未注册的 Skill：" + name, true, HarnessErrorSource.HARNESS));
        }
        log.info("读取 Skill 成功，name={}，version={}，contentCharacters={}", name,
                definition.getIndex().getVersion(), definition.getContent().length());
        return definition;
    }
}
