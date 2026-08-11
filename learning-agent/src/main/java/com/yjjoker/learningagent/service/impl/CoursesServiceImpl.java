package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.domain.CoursesDO;
import com.yjjoker.learningagent.dto.CoursesDTO;
import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.exception.*;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.service.CoursesService;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.CoursesVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

@Service
@AllArgsConstructor
@Slf4j
public class CoursesServiceImpl implements CoursesService {
    private final CoursesRepository coursesRepository;

    //根据id查询课程
    @Override
    public CoursesVO findCourse(Long courseId) {
        Courses courses = getCourse(courseId);
        return toCoursesVO(courses);
    }

    //创建课程
    @Override
    public CoursesVO createCourse(CoursesDTO coursesDTO) {
        Courses courses = new Courses();
        BeanUtils.copyProperties(coursesDTO, courses);
        // 新课程必须从 PRIVATE 开始，客户端不能绕过审核流程指定状态。
        courses.setCourseType(CoursesTypeEnum.PRIVATE);
        courses.setUserId(BaseContext.getCurrentId());
        courses.setPublisherId(BaseContext.getCurrentId());
        courses.setCreatedAt(LocalDateTime.now());
        courses.setUpdatedAt(LocalDateTime.now());
        int affectedRows = coursesRepository.createCourse(courses);
        if (affectedRows != 1) {
            throw new CreateErrorException("系统保存课程出错，稍后再试");
        }
        return toCoursesVO(courses);
    }

    //发布课程
    @Override
    public CoursesVO publishCourse(Long courseId) {
        return changeCourseStatus(courseId, courses -> new CoursesDO().publishCourse(courses, BaseContext.getCurrentId()));
    }

    //通过课程
    @Override
    public CoursesVO passCourse(Long courseId) {
        checkAdmin();
        return changeCourseStatus(courseId, courses -> new CoursesDO().passCourse(courses));
    }

    //驳回或下架课程
    @Override
    public CoursesVO rejectCourse(Long courseId) {
        checkAdmin();
        return changeCourseStatus(courseId, courses -> new CoursesDO().rejectCourse(courses));
    }

    //判断对应课程是否存在且属于当前用户
    @Override
    public void checkUserOwnsCourse(Long courseId){
        Courses courseById = coursesRepository.findCourseById(courseId);
        if (courseById == null) {
            throw new NotFountException("课程不存在");
        }
        if (!Objects.equals(courseById.getUserId(), BaseContext.getCurrentId())) {
            throw new ViolationOperationException("当前用户无权操作该课程");
        }
    }

    //判断对应课程是否存在且属于当前用户 批量检查
    @Override
    public void checkUserOwnsCourses(Set<Long> courseIds) {
        for (Courses course : getCoursesByIds(courseIds)) {
            if (!Objects.equals(course.getUserId(), BaseContext.getCurrentId())) {
                throw new ViolationOperationException("当前用户无权操作这些课程");
            }
        }
    }

    //判断对应课程当前用户是否有权限查看
    @Override
    public void checkUserCanViewCourse(Long courseId) {
        checkUserCanView(getCourse(courseId));
    }

    //判断对应课程当前用户是否有权限查看 批量检查
    @Override
    public void checkUserCanViewCourses(Set<Long> courseIds) {
        for (Courses course : getCoursesByIds(courseIds)) {
            checkUserCanView(course);
        }
    }

    //修改课程状态
    private CoursesVO changeCourseStatus(Long courseId, Consumer<Courses> transition) {
        Courses courses = getCourse(courseId);
        //获取到原始状态，乐观锁防止并发修改冲突
        CoursesTypeEnum expectedStatus = courses.getCourseType();
        // 调用对应的业务逻辑, 更新课程状态
        transition.accept(courses);
        LocalDateTime updatedAt = LocalDateTime.now();
        courses.setUpdatedAt(updatedAt);
        int affectedRows = coursesRepository.updateCourseStatus(courses.getId(), expectedStatus, courses.getCourseType(), updatedAt);
        if (affectedRows != 1) {
            throw new CourseStatusException("课程状态已被其他请求修改，请刷新后重试");
        }
        log.info("课程状态更新成功 courseId={}, {} -> {}", courseId, expectedStatus, courses.getCourseType());
        return toCoursesVO(courses);
    }

    //获取课程
    private Courses getCourse(Long courseId) {
        Courses courses = coursesRepository.findCourseById(courseId);
        if (courses == null) {
            throw new NotFountException("课程不存在");
        }
        return courses;
    }

    private List<Courses> getCoursesByIds(Set<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return List.of();
        }
        if (courseIds.contains(null)) {
            throw new DataIllegalException("课程 ID 不能为空");
        }
        List<Courses> courses = coursesRepository.findCoursesByIds(courseIds);
        Set<Long> foundIds = new HashSet<>();
        for (Courses course : courses) {
            foundIds.add(course.getId());
        }
        if (foundIds.size() != courseIds.size() || !foundIds.containsAll(courseIds)) {
            throw new NotFountException("部分课程不存在");
        }
        return courses;
    }

    //如果当前用户不是课程所有者且课程未发布，则抛出异常
    private void checkUserCanView(Courses course) {
        boolean isOwner = Objects.equals(course.getUserId(), BaseContext.getCurrentId());
        boolean isPublished = course.getCourseType() == CoursesTypeEnum.PUBLISHED;
        if (!isOwner && !isPublished) {
            throw new ViolationOperationException("当前用户无权查看该课程");
        }
    }

    //检查当前用户是否是管理员
    private void checkAdmin() {
        if (BaseContext.getCurrentRole() != UserRoleEnum.ADMIN) {
            throw new ViolationOperationException("需要管理员权限");
        }
    }

    //转换课程
    private CoursesVO toCoursesVO(Courses courses) {
        CoursesVO coursesVO = new CoursesVO();
        BeanUtils.copyProperties(courses, coursesVO);
        coursesVO.setId(courses.getId());
        return coursesVO;
    }
}
