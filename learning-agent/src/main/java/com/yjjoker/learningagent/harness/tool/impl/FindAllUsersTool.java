package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.entity.User;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.repository.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

// @Component 让 Spring 在启动时创建这个工具对象，随后将它收集进 ToolRegistry 的 List<Tool>。
// @AllArgsConstructor 由 Lombok 生成包含 userRepository 参数的构造方法，Spring 用它完成依赖注入。
@AllArgsConstructor
@Component
public class FindAllUsersTool implements Tool {

    // Repository 负责访问数据库，工具本身只负责把查询能力包装成 LLM 可以申请调用的形式。
    private final UserRepository userRepository;

    @Override
    public String name() {
        // 这是模型响应与 Java 工具之间的唯一匹配名称，修改时必须同时考虑模型侧的工具定义。
        return "find_all_users";
    }

    @Override
    public String description() {
        // 说明会发送给模型，模型根据用户问题和这段说明判断是否需要使用当前工具。
        return "查找到目前所有用户的用户名";
    }

    @Override
    public ToolExecutionResult execute(String input) {
        // 当前工具没有参数，所以不会读取 input；它通常是模型生成的空 JSON 对象字符串 "{}"。
        List<User> users = userRepository.findAllUsers();

        // 明确返回“没有用户”比返回空字符串更好，因为模型能理解这是一次成功但无数据的查询。
        if (users.isEmpty()) {
            return ToolExecutionResult.success("没有查找到用户");
        }

        // 工具只提供原始事实，不负责生成面向用户的完整回答；最终表述会由下一轮 LLM 完成。
        // joining 可以避免手动拼接产生末尾多余的逗号和空格。
        String usernames = users.stream()
                .map(User::getUsername)
                .collect(Collectors.joining(", "));
        return ToolExecutionResult.success(usernames);
    }
}
