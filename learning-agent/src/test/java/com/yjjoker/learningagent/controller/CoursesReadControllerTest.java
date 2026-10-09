package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.handler.ServiceExceptionHandler;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.service.CoursesService;
import com.yjjoker.learningagent.vo.CoursesVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// MVC 使用真实路由和序列化，服务替身避免请求真实数据库。
class CoursesReadControllerTest {
    private final CoursesService service = mock(CoursesService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CoursesController(service))
            .setControllerAdvice(new ServiceExceptionHandler()).build();

    // 列表路由返回现有 CoursesVO 数组；请求自带 userId 不会转交服务冒充其他用户。
    @Test
    void returnsOwnCourseListAndIgnoresClientUserId() throws Exception {
        when(service.findCurrentUserCourses()).thenReturn(List.of(course()));
        mvc.perform(get("/learning-agent/courses").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(11))
                .andExpect(jsonPath("$.data[0].courseName").value("Java"))
                .andExpect(jsonPath("$.data[0].courseType").value("PRIVATE"));
        verify(service).findCurrentUserCourses();
        verifyNoMoreInteractions(service);
    }

    // 课程编号从路径绑定，编辑详情返回大纲等原有 VO 字段。
    @Test
    void returnsOwnedCourseDetail() throws Exception {
        when(service.findOwnedCourse(11L)).thenReturn(course());
        mvc.perform(get("/learning-agent/courses/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.learningOutline").value("课程大纲"));
        verify(service).findOwnedCourse(11L);
    }

    // 归属查询失败通过现有异常处理返回统一提示，不返回别人的课程正文。
    @Test
    void doesNotReturnDataWhenCourseIsMissingOrForeign() throws Exception {
        when(service.findOwnedCourse(11L)).thenThrow(new NotFountException("课程不存在或无权查看"));
        mvc.perform(get("/learning-agent/courses/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("课程不存在或无权查看"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // 非数字路径不能绑定课程编号，在服务调用前由 MVC 拒绝。
    @Test
    void rejectsMalformedCourseId() throws Exception {
        mvc.perform(get("/learning-agent/courses/not-a-number"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    // 生成可显示课程结果，测试聚焦读取接口而不复用创建或发布状态流转。
    private CoursesVO course() {
        CoursesVO course = new CoursesVO();
        course.setId(11L);
        course.setCourseName("Java");
        course.setCourseType(CoursesTypeEnum.PRIVATE);
        course.setLearningOutline("课程大纲");
        return course;
    }
}
