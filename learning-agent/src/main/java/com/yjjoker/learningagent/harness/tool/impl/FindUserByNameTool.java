package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.entity.User;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.repository.UserRepository;
import com.yjjoker.learningagent.vo.UserVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

@AllArgsConstructor
@Component
public class FindUserByNameTool implements Tool {

    // JSON 解析器
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private final UserRepository userRepository;

    @Override
    public String name() {
        return "find_user_by_name";
    }

    @Override
    public String description() {
        return "根据准确的用户名查找用户的信息，请提供 username 参数";
    }

    @Override
    public ToolExecutionResult execute(String input) {
        //Harness 传来的 input 是整个 JSON 字符串，如 {"username":"张三"}，不是直接可用于查询的用户名。
        //先检查字符串是否存在，再用 Jackson 将它解析成可按字段读取的 JSON 节点。
        if (input == null || input.isBlank()) {
            return invalidArgument("工具参数不能为空，需要提供 username");
        }
        JsonNode arguments;
        try {
            arguments = JSON_MAPPER.readTree(input);
        } catch (JacksonException exception) {
            // 这是模型可以修正的参数错误，不需要用异常中断整个 Agent Loop。
            return invalidArgument("工具参数不是有效的 JSON 对象");
        }

        // 解析 JSON 节点，必须只包含 username 字段
        if (arguments == null || !arguments.isObject() || arguments.size() != 1) {
            return invalidArgument("工具参数必须是只包含 username 的 JSON 对象");
        }

        JsonNode usernameNode = arguments.get("username");
        if (usernameNode == null || !usernameNode.isTextual() || usernameNode.asString().isBlank()) {
            return invalidArgument("username 必须是非空字符串");
        }

        String username = usernameNode.asString().trim();
        User userByUsername = userRepository.findUserByUsername(username);
        if (userByUsername == null) {
            // 未查到用户属于正常查询结果，不能把 null 交给 BeanUtils.copyProperties。
            return ToolExecutionResult.success("未找到用户名为 " + username + " 的用户");
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(userByUsername, userVO);
        return ToolExecutionResult.success(userVO.toString());
    }

    // 参数错误使用同一个错误码，并允许模型按照 message 修正参数后重新调用。
    private ToolExecutionResult invalidArgument(String message) {
        return ToolExecutionResult.failure("INVALID_ARGUMENT", message, true);
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                // 外层 type=object：模型要提供一个 JSON 对象，而不是一个裸字符串，例如 {"username":"张三"}。
                "type", "object",
                // properties：这个对象可以包含什么字段，以及每个字段应该是什么类型。
                "properties", Map.of(
                        "username", Map.of(
                                "type", "string",
                                // description：这个字段的描述，用于提示模型提供什么类型的值。
                                "description", "要查找的准确用户名"
                        )
                ),
                // required：虽然 username 出现在 properties 中，但还必须单独声明它是必填的。
                "required", List.of("username"),
                // additionalProperties=false：不允许模型添加未声明的字段，例如 age 或 password。
                "additionalProperties", false
        );
    }
}
