package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.UserDTO;
import com.yjjoker.learningagent.entity.User;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("用户服务测试")
@ExtendWith(MockitoExtension.class)
public class UserServiceImplTest {
    @Mock
    private UserRepository userRepository;
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
}
