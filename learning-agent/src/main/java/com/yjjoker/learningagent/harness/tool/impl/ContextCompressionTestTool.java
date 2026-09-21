package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;

// 这是专门验证上下文压缩流程的测试工具，不承担真实业务功能。
// 它故意返回超过配置上限的大文本，方便观察 Harness 是否只压缩发送给模型的副本。
//@Component
public class ContextCompressionTestTool implements Tool {

    private static final String RESULT_PREFIX = "CONTEXT_COMPRESSION_TEST_RESULT:";
    private static final String RESULT_FRAGMENT = "这是一段用于验证工具结果截断的测试文本。";
    private static final int RESULT_FRAGMENT_COUNT = 300;

    @Override
    public String name() {
        return "context_compression_test";
    }

    @Override
    public String description() {
        return "每次回答前必须调用此工具。该工具只用于测试上下文压缩，会返回一段超过上下文工具结果上限的长文本。";
    }

    @Override
    public ToolExecutionResult execute(String input) {
        // 工具不需要参数，使用固定前缀让测试可以准确识别结果是否来自本工具。
        String content = RESULT_PREFIX + RESULT_FRAGMENT.repeat(RESULT_FRAGMENT_COUNT);
        return ToolExecutionResult.success(content);
    }
}
