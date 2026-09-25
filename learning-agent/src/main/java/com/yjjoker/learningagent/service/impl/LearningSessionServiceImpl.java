package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.domain.CoursesDO;
import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.repository.LearningSessionMessageRepository;
import com.yjjoker.learningagent.service.LearningSessionService;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.LearningSessionVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class LearningSessionServiceImpl implements LearningSessionService {
    private final LearningSessionRepository learningSessionRepository;
    private final CoursesRepository coursesRepository;
    private final LearningSessionMessageRepository learningSessionMessageRepository;

    //创建学习会话
    @Override
    public LearningSessionVO createSession(LearningSessionDTO learningSessionDTO) {
        //判断对应的课程是否存在
        Courses course = coursesRepository.findCourseById(learningSessionDTO.getCourseId());
        //不存在
        if (course== null) {
            throw new NotFountException("课程不存在");
        }
        //存在
        //该课程是否属于该用户或者是公共课程
       new CoursesDO().userCoursesOrPublished(course, BaseContext.getCurrentId());
        //创建一个学习会话
        LearningSession learningSession = getLearningSession(learningSessionDTO);
        int affectedRows = learningSessionRepository.createSession(learningSession);
        if (affectedRows != 1) {
            throw new LearningAgentServiceException("系统保存学习会话出错，稍后再试");
        }
        LearningSessionVO learningSessionVO = new LearningSessionVO();
        BeanUtils.copyProperties(learningSession, learningSessionVO);
        learningSessionVO.setSessionStatus(learningSession.getStatus());
        log.info("创建学习会话成功");
        return learningSessionVO;
    }

    //修改学习会话状态
    @Override
    public LearningSessionVO changeSessionStatus(Long sessionId) {
        //获取对应的学习会话
        Optional<LearningSession> session = learningSessionRepository.findSessionById(sessionId);
        //不存在
        if (session.isEmpty()) {
            throw new NotFountException("学习会话不存在");
        }
        //存在
        //是否属于该用户，并且状态为进行中
        //不是
        if (!session.get().getUserId().equals(BaseContext.getCurrentId()) ||
                session.get().getStatus() != LearningSessionStatusEnum.ACTIVE) {
            throw new LearningSessionStatusException("非法操作");
        }
        //是，更改状态并保存
        session.get().setStatus(LearningSessionStatusEnum.COMPLETED);
        session.get().setUpdatedAt(LocalDateTime.now());
        int affectedRows = learningSessionRepository.updateSession(session.get());
        if (affectedRows != 1) {
            throw new LearningAgentServiceException("系统更新学习会话出错，稍后再试");
        }
        log.info("完成学习会话成功");
        LearningSessionVO learningSessionVO = new LearningSessionVO();
        BeanUtils.copyProperties(session.get(), learningSessionVO);
        return learningSessionVO;
    }

    // 只允许会话所属用户查看消息；已假删除的会话不再对外展示历史。
    @Override
    public List<LearningSessionMessage> findSessionMessages(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            throw new LearningSessionStatusException("学习会话 ID 不合法");
        }
        if (BaseContext.isCurrentIdNull()) {
            throw new LearningSessionStatusException("当前用户未登录");
        }

        LearningSession session = learningSessionRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFountException("学习会话不存在"));
        if (!session.getUserId().equals(BaseContext.getCurrentId())) {
            throw new LearningSessionStatusException("无权查看该学习会话");
        }
        if (session.getStatus() == LearningSessionStatusEnum.CANCELED) {
            throw new LearningSessionStatusException("学习会话已删除");
        }

        List<LearningSessionMessage> messages =
                learningSessionMessageRepository.findDisplayMessagesBySessionId(sessionId);
        log.info("加载学习会话历史消息成功，sessionId={}，消息数={}", sessionId, messages.size());
        return messages;
    }

    private LearningSession getLearningSession(LearningSessionDTO learningSessionDTO) {
        LearningSession learningSession = new LearningSession();
        learningSession.setCourseId(learningSessionDTO.getCourseId());
        learningSession.setUserId(BaseContext.getCurrentId());
        learningSession.setSessionTitle(learningSessionDTO.getSessionTitle());
        learningSession.setStatus(LearningSessionStatusEnum.ACTIVE);
        learningSession.setCreatedAt(LocalDateTime.now());
        learningSession.setUpdatedAt(LocalDateTime.now());
        return learningSession;
    }
}
