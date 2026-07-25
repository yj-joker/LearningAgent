package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.CourseNotFountException;
import com.yjjoker.learningagent.exception.CreateErrorException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.service.LearningSessionService;
import com.yjjoker.learningagent.vo.LearningSessionVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@AllArgsConstructor
@Slf4j
public class LearningSessionServiceImpl implements LearningSessionService {
    private final LearningSessionRepository learningSessionRepository;
    private final CoursesRepository coursesRepository;
    @Override
    public LearningSessionVO createSession(LearningSessionDTO learningSessionDTO) {
        //判断对应的课程是否存在
        Optional<Courses> course = coursesRepository.findCourseById(learningSessionDTO.getCourseId());
        //不存在
        if(course.isEmpty()){
            throw new CourseNotFountException("课程不存在");
        }
        //存在
        //该课程是否属于该用户或者是公共课程
        Boolean userCoursesOrPublic = course.get().isUserCoursesOrPublic(learningSessionDTO.getUserId(), learningSessionDTO.getCourseId());
        if(!userCoursesOrPublic){
            throw new CreateErrorException("非法创建");
        }
        //创建一个学习会话
        Optional<LearningSession> session = learningSessionRepository.createSession(learningSessionDTO);
        if(session.isEmpty()){
            throw new LearningAgentServiceException("系统保存学习会话出错，稍后再试");
        }
        LearningSession learningSession=session.get();
        LearningSessionVO learningSessionVO=new LearningSessionVO();
        BeanUtils.copyProperties(learningSession,learningSessionVO);
        log.info("创建学习会话成功");
        return learningSessionVO;
    }
}
