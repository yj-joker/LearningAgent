package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.page.PageResult;
import com.yjjoker.learningagent.page.UserPageRequest;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.service.UserService;
import com.yjjoker.learningagent.vo.UserPageItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@DisplayName("用户分页接口测试")
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = standaloneSetup(new UserController(userService))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("GET 查询参数应绑定到分页请求并返回分页元数据")
    void shouldBindQueryParametersAndReturnPage() throws Exception {
        when(userService.query(any(UserPageRequest.class))).thenAnswer(invocation -> {
            UserPageRequest request = invocation.getArgument(0);
            UserPageItemVO item = new UserPageItemVO(
                    "10001",
                    "alice",
                    null,
                    UserRoleEnum.USER,
                    LocalDateTime.of(2026, 1, 1, 10, 0),
                    LocalDateTime.of(2026, 1, 1, 10, 0));
            return PageResult.of(request, 25, List.of(item));
        });

        mockMvc.perform(get("/learning-agent/user/pageQuery")
                        .param("page", "2")
                        .param("size", "10")
                        .param("username", "alice")
                        .param("role", "USER")
                        .param("createdAtStart", "2026-01-01T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(25))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasPrevious").value(true))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value("10001"));

        ArgumentCaptor<UserPageRequest> captor = ArgumentCaptor.forClass(UserPageRequest.class);
        verify(userService).query(captor.capture());
        assertEquals(2, captor.getValue().getPage());
        assertEquals(10, captor.getValue().getSize());
        assertEquals("alice", captor.getValue().getUsername());
        assertEquals(UserRoleEnum.USER, captor.getValue().getRole());
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), captor.getValue().getCreatedAtStart());
    }

    @Test
    @DisplayName("每页数量超过上限时应返回 HTTP 400")
    void shouldRejectPageSizeOverLimit() throws Exception {
        mockMvc.perform(get("/learning-agent/user/pageQuery")
                        .param("page", "1")
                        .param("size", "101"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).query(any(UserPageRequest.class));
    }

    @Test
    @DisplayName("创建时间开始值晚于结束值时应返回 HTTP 400")
    void shouldRejectInvalidCreatedAtRange() throws Exception {
        mockMvc.perform(get("/learning-agent/user/pageQuery")
                        .param("createdAtStart", "2026-02-01T00:00:00")
                        .param("createdAtEnd", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).query(any(UserPageRequest.class));
    }
}
