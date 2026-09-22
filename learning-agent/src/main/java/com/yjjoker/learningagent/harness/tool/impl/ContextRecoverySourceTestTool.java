package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

// 只在 harness-test-tools 环境启用，用于验证“结果被截断后再恢复原文”的真实模型链路。
@Component
@Profile("harness-test-tools")
public class ContextRecoverySourceTestTool implements Tool {

    private static final String ORIGINAL_MARKER = "RECOVERY_SOURCE_ORIGINAL:";

    @Override
    public String name() {
        return "context_recovery_source_test";
    }

    @Override
    public String description() {
        return "测试工具。请先调用它获取资料；如果结果显示已截断，再调用 get_original_tool_result 读取原始片段。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false
        );
    }

    @Override
    public ToolExecutionResult execute(String input) {
        // 固定标记便于确认恢复结果确实来自完整原文，而不是压缩副本。
        return ToolExecutionResult.success(
                ORIGINAL_MARKER + "关键恢复内容：数据库事务必须满足原子性、一致性、隔离性和持久性。"
                        .repeat(80)
        );
    }
}
