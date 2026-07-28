package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.UserDTO;
import com.yjjoker.learningagent.entity.User;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.PasswordErrorException;
import com.yjjoker.learningagent.page.PageResult;
import com.yjjoker.learningagent.page.UserPageRequest;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.repository.UserRepository;
import com.yjjoker.learningagent.utils.JwtService;
import com.yjjoker.learningagent.utils.SnowflakeIdGenerator;
import com.yjjoker.learningagent.vo.UserPageItemVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("用户服务测试")
@ExtendWith(MockitoExtension.class)
public class UserServiceImplTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @InjectMocks
    private UserServiceImpl userServiceImpl;
    @Test
    @DisplayName("用户注册成功")
    void shouldRegisterUserSuccess() {
        UserDTO userDTO = new UserDTO("yj","123456");
        when(userRepository.save(any())).thenReturn(1);
        when(snowflakeIdGenerator.nextId()).thenReturn(10001L);
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
        user.setUsername("yjjoker");
        user.setRole(UserRoleEnum.USER);
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
        user.setRole(UserRoleEnum.USER);
        when(userRepository.findUserByUsername(userDTO.getUsername())).thenReturn(user);
        when(jwtService.createToken(user.getId(), user.getRole())).thenReturn("token");
        assertDoesNotThrow(() -> userServiceImpl.login(userDTO));
    }

    @Test
    @DisplayName("分页查询应返回稳定的分页元数据和脱敏用户数据")
    void shouldQueryUsersByPage() {
        UserPageRequest request = new UserPageRequest();
        request.setPage(2);
        request.setSize(2);
        request.setUsername("  ali  ");
        request.setRole(UserRoleEnum.USER);

        User firstUser = user(103L, "alice");
        User secondUser = user(102L, "bob");
        when(userRepository.countByCondition(same(request))).thenReturn(5L);
        when(userRepository.findPageByCondition(same(request), anyLong(), anyInt()))
                .thenReturn(List.of(firstUser, secondUser));

        PageResult<UserPageItemVO> result = userServiceImpl.query(request);

        assertEquals(2, result.getPage());
        assertEquals(2, result.getSize());
        assertEquals(5, result.getTotalElements());
        assertEquals(3, result.getTotalPages());
        assertTrue(result.isHasPrevious());
        assertTrue(result.isHasNext());
        assertEquals(2, result.getItems().size());
        assertEquals("103", result.getItems().getFirst().getId());
        assertEquals("alice", result.getItems().getFirst().getUsername());
        assertEquals("ali", request.getUsername());
        verify(userRepository).findPageByCondition(request, 2L, 2);
    }

    @Test
    @DisplayName("页码超过最后一页时应返回空列表且不执行无意义的明细查询")
    void shouldReturnEmptyPageWhenPageIsOutOfRange() {
        UserPageRequest request = new UserPageRequest();
        request.setPage(4);
        request.setSize(2);
        when(userRepository.countByCondition(request)).thenReturn(5L);

        PageResult<UserPageItemVO> result = userServiceImpl.query(request);

        assertEquals(3, result.getTotalPages());
        assertTrue(result.isHasPrevious());
        assertFalse(result.isHasNext());
        assertTrue(result.getItems().isEmpty());
        verify(userRepository, never())
                .findPageByCondition(any(UserPageRequest.class), anyLong(), anyInt());
    }

    @Test
    @DisplayName("数据库分页查询失败时应转换为业务异常")
    void shouldConvertPageQueryDatabaseError() {
        UserPageRequest request = new UserPageRequest();
        when(userRepository.countByCondition(request))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        assertThrows(LearningAgentServiceException.class, () -> userServiceImpl.query(request));
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setAvatarUrl("https://example.com/" + username + ".png");
        user.setRole(UserRoleEnum.USER);
        user.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        user.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 10, 0));
        return user;
    }
}
