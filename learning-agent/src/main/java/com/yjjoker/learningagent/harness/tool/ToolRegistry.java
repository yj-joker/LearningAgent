package com.yjjoker.learningagent.harness.tool;

import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 工具注册表负责集中保存当前应用可以使用的所有 Tool。
// 它只负责注册和查找，不决定应该调用哪个工具，也不负责执行 Harness 循环。
@Component
public class ToolRegistry {

    // Map 的 key 是工具名称，value 是对应的工具对象。
    // 使用 Map 后可以根据名称直接查找，不需要每次遍历整个工具列表。
    private final Map<String, Tool> toolsByName;

    // Spring 会收集容器中所有实现了 Tool 接口的组件，并以 List<Tool> 的形式传进来。
    // 当前还没有真实工具时，这个列表可以为空；以后新增工具不需要修改注册表代码。
    public ToolRegistry(List<Tool> tools) {
        Map<String, Tool> registeredTools = new LinkedHashMap<>();

        for (Tool tool : tools) {
            String toolName = tool.name();

            // 工具名称是 LLM 与 Java 实现之间的定位标识，因此不能为 null 或空字符串。
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalStateException("工具名称不能为空");
            }

            // putIfAbsent 只在名称尚未注册时写入，并返回之前已经存在的工具。
            // 这里不允许重复名称，遇到时立即报错。
            Tool existingTool = registeredTools.putIfAbsent(toolName, tool);
            if (existingTool != null) {
                throw new IllegalStateException("存在重复的工具名称：" + toolName);
            }
        }

        // 对外保存只读 Map，避免注册完成后其他代码随意添加或删除工具，造成运行状态不一致。
        this.toolsByName = Collections.unmodifiableMap(registeredTools);
    }

    // 根据 LLM 返回的工具名称取得对应实现。
    // 找不到时立即抛出明确异常，比返回 null 并在后续触发空指针更容易定位问题。
    public Tool getRequiredTool(String toolName) {
        Tool tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new LearningAgentServiceException("模型请求了未注册的工具：" + toolName);
        }
        return tool;
    }

    // 返回全部工具的只读列表，后续会用它把工具名称和说明提供给 LLM。
    // List.copyOf 会创建不可修改的快照，调用方不能借此改变注册表内部内容。
    public List<Tool> getAllTools() {
        return List.copyOf(toolsByName.values());
    }
}
