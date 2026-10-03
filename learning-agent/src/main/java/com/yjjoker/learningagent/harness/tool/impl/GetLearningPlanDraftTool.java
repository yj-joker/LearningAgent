package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.learningplan.service.LearningPlanDraftToolService;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

// 读取一份草案的完整内容；只读工具不需要用户审批。
@Component
@RequiredArgsConstructor
public class GetLearningPlanDraftTool implements Tool {
    private final LearningPlanDraftToolService service;

    @Override
    public String name() { return "get_learning_plan_draft"; }

    // 只有需要完整步骤时才读取正文，减少无关上下文。
    @Override
    public String description() {
        return "按当前学习计划索引中的 draftRef 读取完整字段和步骤。DRAFT 尚未生效，ACTIVE 是正式计划；正式生效不代表已经开始专注执行。";
    }

    @Override
    public boolean isContextScopedTool() { return true; }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "draftRef", Map.of("type", "string", "description", "复制 list_learning_plan_drafts 返回的完整 draftRef")),
                "required", java.util.List.of("draftRef"), "additionalProperties", false);
    }

    @Override
    public ToolExecutionResult execute(String input) { return service.get(input); }
}
