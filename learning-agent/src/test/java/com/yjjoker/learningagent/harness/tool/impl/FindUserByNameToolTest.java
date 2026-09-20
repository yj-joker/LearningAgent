package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.entity.User;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("按用户名查找用户的工具测试")
class FindUserByNameToolTest {

    // 这里只验证工具的参数声明、JSON 解析和查询结果；用假 Repository 代替真实数据库。
    private final UserRepository userRepository = mock(UserRepository.class);
    private final FindUserByNameTool tool = new FindUserByNameTool(userRepository);

    @Test
    @DisplayName("声明必填的 username 字符串参数并禁止额外字段")
    void shouldDescribeUsernameParameter() {
        Map<String, Object> schema = tool.parametersSchema();

        // 外层描述整个参数对象，内层描述 username 的类型，required 才真正标记必填。
        assertEquals("object", schema.get("type"));
        assertEquals(List.of("username"), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));

        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        Map<?, ?> username = (Map<?, ?>) properties.get("username");
        assertEquals("string", username.get("type"));
    }

    @Test
    @DisplayName("从 JSON 中提取用户名再查询，不把密码交给模型")
    void shouldQueryWithParsedUsername() {
        User user = new User();
        user.setUsername("张三");
        user.setPassword("test-private-password");
        when(userRepository.findUserByUsername("张三")).thenReturn(user);

        ToolExecutionResult result = tool.execute("{\"username\":\"张三\"}");

        // 验证 Repository 收到的是用户名本身，而不是完整的 JSON 字符串。
        verify(userRepository).findUserByUsername("张三");
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("张三"));
        assertFalse(result.getContent().contains("test-private-password"));
    }

    @Test
    @DisplayName("查无此人时返回明确结果")
    void shouldReturnNotFoundInsteadOfCopyingNull() {
        ToolExecutionResult result = tool.execute("{\"username\":\"不存在的用户\"}");

        verify(userRepository).findUserByUsername("不存在的用户");
        assertTrue(result.isSuccess());
        assertEquals("未找到用户名为 不存在的用户 的用户", result.getContent());
    }

    @Test
    @DisplayName("拒绝缺失、非字符串和额外参数，避免错误查询")
    void shouldRejectInvalidFields() {
        assertInvalidArgument(tool.execute("{}"));
        assertInvalidArgument(tool.execute("{\"username\":123}"));
        assertInvalidArgument(tool.execute("{\"username\":\"  \"}"));
        assertInvalidArgument(tool.execute("{\"username\":\"张三\",\"age\":20}"));

        // 参数不合法时不能访问数据库，否则很难区分参数问题和“用户不存在”。
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("拒绝不符合 JSON 语法的输入")
    void shouldRejectMalformedJson() {
        assertInvalidArgument(tool.execute("不是 JSON"));
        verifyNoInteractions(userRepository);
    }

    private void assertInvalidArgument(ToolExecutionResult result) {
        // 参数错误没有抛出异常，而是变成 LLM 可以读取并修正的结构化失败结果。
        assertFalse(result.isSuccess());
        assertEquals("INVALID_ARGUMENT", result.getErrorCode());
        assertTrue(result.isRetryable());
    }
}
