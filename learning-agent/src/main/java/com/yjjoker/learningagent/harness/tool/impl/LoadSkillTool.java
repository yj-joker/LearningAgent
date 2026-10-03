package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.error.HarnessException;
import com.yjjoker.learningagent.harness.skill.service.SkillRunContext;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

// 读取内置技能，不执行脚本或业务写入；后续工具仍通过原 Hook 与审批。
@Component
@RequiredArgsConstructor
@Slf4j
public class LoadSkillTool implements Tool {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final SkillRunContext skills;

    // 工具名用于模型请求，文件路径和 Java 类名不暴露给模型。
    @Override
    public String name() { return "load_skill"; }

    // 由主模型根据当前任务判断是否需要技能，不增加关键词路由或独立意图模型。
    @Override
    public String description() {
        return "按可用技能索引中的 name 和 version 加载内置流程。普通问答不必加载；任务需要对应流程时单独调用，"
                + "正文在下一次模型请求的已加载技能区提供。重复加载不会重复注入；加载不代表执行业务操作或获得授权。";
    }

    // 先读取工作方法，再生成后续动作，不能与尚未阅读技能时猜出的业务操作同批执行。
    @Override
    public boolean requiresExclusiveBatch() { return true; }

    // 激活只对本次任务有效，旧回执不能在下一轮被误认为技能仍然激活。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 只接收名称和版本，不提供路径、URL、用户编号或工具权限参数。
    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "name", Map.of("type", "string", "description", "复制当前技能索引中的 name", "maxLength", 64),
                "version", Map.of("type", "string", "description", "复制同一条索引中的 version", "maxLength", 64)),
                "required", List.of("name", "version"), "additionalProperties", false);
    }

    // 参数正确才激活技能；格式、未知名称和版本失败都作为工具结果回给模型。
    @Override
    public ToolExecutionResult execute(String input) {
        if (input == null || input.length() > 1024) return invalidInput();
        try {
            var root = JSON.readTree(input);
            if (root == null || !root.isObject() || root.size() != 2
                    || !root.path("name").isTextual() || !root.path("version").isTextual()
                    || root.path("version").asString().isBlank() || root.path("version").asString().length() > 64) {
                return invalidInput();
            }
            return skills.load(root.path("name").asString(), root.path("version").asString());
        } catch (JacksonException exception) {
            return invalidInput();
        } catch (HarnessException exception) {
            // 统一错误对象保留是否可修正，不抛出原始文件或解析异常。
            log.warn("Skill 工具执行失败，errorCode={}", exception.getErrorCode());
            return ToolExecutionResult.failure(exception.getError());
        }
    }

    // 参数可重新生成，但不能通过修正参数绕过已有业务权限和审批。
    private ToolExecutionResult invalidInput() {
        log.warn("Skill 参数校验失败，errorCode=INVALID_TOOL_ARGUMENTS");
        return ToolExecutionResult.failure("INVALID_TOOL_ARGUMENTS", "参数必须是只含 name 和 version 字符串的 JSON 对象", true);
    }
}
