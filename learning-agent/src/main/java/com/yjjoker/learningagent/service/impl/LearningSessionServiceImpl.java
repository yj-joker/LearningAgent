package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.domain.CoursesDO;
import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.dto.StandaloneLearningSessionDTO;
import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.exception.LearningSessionStatusException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.repository.LearningSessionMessageRepository;
import com.yjjoker.learningagent.service.LearningSessionService;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.LearningSessionVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    // 保留课程创建入口：先确认课程存在与可见，再保存当前用户的课程会话。
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
        LearningSessionVO learningSessionVO = toView(learningSession);
        learningSessionVO.setCourseName(course.getCourseName());
        log.info("创建课程学习会话成功，userId={}，sessionId={}，courseId={}，mode={}",
                learningSession.getUserId(), learningSession.getId(), learningSession.getCourseId(), learningSession.getMode());
        return learningSessionVO;
    }

    // 独立会话不借用课程编号；创建新编号让消息、记忆和专注计划都拥有新的范围。
    @Override
    public LearningSessionVO createStandaloneSession(StandaloneLearningSessionDTO request) {
        Long userId = requireCurrentUser();
        // 服务内部也校验输入，防止非 HTTP 调用绕过 DTO 的校验注解。
        if (request == null || request.getSessionTitle() == null || request.getSessionTitle().isBlank()) {
            throw new ClientDataErrorException("学习会话标题不能为空");
        }
        String title = request.getSessionTitle().strip();
        if (title.length() > 255) {
            throw new ClientDataErrorException("学习会话标题不能超过 255 个字符");
        }
        if (request.getMode() != AgentMode.CHAT && request.getMode() != AgentMode.FOCUS) {
            throw new ClientDataErrorException("独立会话只支持问答或专注模式");
        }
        LocalDateTime now = LocalDateTime.now();
        LearningSession session = new LearningSession();
        session.setUserId(userId);
        session.setSessionTitle(title);
        session.setMode(request.getMode());
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        // 单行插入由数据库生成编号；失败时不向前端返回一个虚假的已保存会话。
        if (learningSessionRepository.createSession(session) != 1) {
            throw new LearningAgentServiceException("系统保存学习会话出错，稍后再试");
        }
        log.info("创建独立学习会话成功，userId={}，sessionId={}，mode={}", userId, session.getId(), session.getMode());
        return toView(session);
    }

    // 列表来自数据库而不是浏览器缓存，用户编号只取后端登录上下文。
    @Override
    public List<LearningSessionVO> findSessions() {
        Long userId = requireCurrentUser();
        List<LearningSessionVO> sessions = learningSessionRepository.findVisibleSessionsByUserId(userId);
        log.info("加载学习会话列表成功，userId={}，会话数={}", userId, sessions.size());
        return sessions;
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
        log.info("完成学习会话成功，userId={}，sessionId={}", BaseContext.getCurrentId(), sessionId);
        return toView(session.get());
    }

    // 只允许会话所属用户查看消息；已假删除的会话不再对外展示历史。
    @Override
    public List<LearningSessionMessage> findSessionMessages(Long sessionId) {
        // 旧调用不指定模式，仍可查看过去课程会话中全部问答记录。
        return findSessionMessages(sessionId, null);
    }

    // 同一个课程会话可能有旧的不同模式历史，指定模式后只展示对应记录。
    @Override
    public List<LearningSessionMessage> findSessionMessages(Long sessionId, AgentMode mode) {
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

        List<LearningSessionMessage> messages = mode == null
                ? learningSessionMessageRepository.findDisplayMessagesBySessionId(sessionId)
                : learningSessionMessageRepository.findDisplayMessagesBySessionIdAndMode(sessionId, mode.name());
        log.info("加载学习会话历史消息成功，sessionId={}，mode={}，消息数={}", sessionId, mode, messages.size());
        return messages;
    }

    // 把课程请求转成待保存会话，创建时间与更新时间使用同一个时间点。
    private LearningSession getLearningSession(LearningSessionDTO learningSessionDTO) {
        LearningSession learningSession = new LearningSession();
        learningSession.setCourseId(learningSessionDTO.getCourseId());
        learningSession.setMode(AgentMode.COURSE);
        learningSession.setUserId(BaseContext.getCurrentId());
        learningSession.setSessionTitle(learningSessionDTO.getSessionTitle());
        learningSession.setStatus(LearningSessionStatusEnum.ACTIVE);
        learningSession.setCreatedAt(LocalDateTime.now());
        learningSession.setUpdatedAt(learningSession.getCreatedAt());
        return learningSession;
    }

    // 显式映射状态和时间，修复旧字段名不同导致状态或时间为空的问题。
    private LearningSessionVO toView(LearningSession session) {
        LearningSessionVO view = new LearningSessionVO();
        view.setId(session.getId());
        view.setSessionTitle(session.getSessionTitle());
        view.setSessionStatus(session.getStatus());
        view.setCourseId(session.getCourseId());
        view.setMode(session.getMode());
        view.setCreatedAt(session.getCreatedAt());
        view.setUpdatedAt(session.getUpdatedAt());
        view.setCreateAt(session.getCreatedAt());
        view.setUpdateAt(session.getUpdatedAt());
        return view;
    }

    // 请求不能传用户编号；没有有效登录身份就不查询或创建会话。
    private Long requireCurrentUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) {
            throw new LearningSessionStatusException("当前用户未登录");
        }
        return userId;
    }
}
