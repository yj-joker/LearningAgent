package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.UserDTO;
import com.yjjoker.learningagent.entity.User;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.exception.PasswordErrorException;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.repository.UserRepository;
import com.yjjoker.learningagent.utils.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("用户服务测试")
@ExtendWith(MockitoExtension.class)
public class UserServiceImplTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;
    @InjectMocks
    private UserServiceImpl userServiceImpl;
    @Test
    @DisplayName("用户注册成功")
    void shouldRegisterUserSuccess() {
        UserDTO userDTO = new UserDTO("yj","123456");
        when(userRepository.save(any())).thenReturn(1);
        assertDoesNotThrow(() -> userServiceImpl.register(userDTO));
    }
    @Test
    @DisplayName("用户名重复注册失败")
    void shouldRegisterUserFail() {
        UserDTO userDTO = new UserDTO("yj","123456");
        when(userRepository.findUserByUsername(userDTO.getUsername())).thenReturn(new User());
        assertThrows(CreateErrorException.class, () -> userServiceImpl.register(userDTO));
    }
    @Test
    @DisplayName("用户密码错误时应抛出异常")
    void shouldThrowExceptionWhenPasswordError() {
        UserDTO userDTO = new UserDTO("yj","123456");
        User user = new User();
        user.setPassword("1234567890");
        user.setUsername("yj");
        user.setUserType(UserRoleEnum.USER);
        when(userRepository.findUserByUsername(userDTO.getUsername())).thenReturn(user);
        assertThrows(PasswordErrorException.class, () -> userServiceImpl.login(userDTO));
    }
    @Test
    @DisplayName("用户登录成功")
    void shouldLoginUserSuccess() {
        UserDTO userDTO = new UserDTO("yj","123456");
        User user = new User();
        user.setUsername("yj");
        user.setPassword(BCrypt.hashpw(userDTO.getPassword(), BCrypt.gensalt()));
        user.setUserType(UserRoleEnum.USER);
        when(userRepository.findUserByUsername(userDTO.getUsername())).thenReturn(user);
        when((jwtService.createToken(user.getId(), user.getUserType().name()))).thenReturn("token");
        assertDoesNotThrow(() -> userServiceImpl.login(userDTO));
    }
}
