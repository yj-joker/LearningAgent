package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
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
    @Override
    public LearningSessionVO createSession(LearningSessionDTO learningSessionDTO) {
        //判断对应的用户课程是否存在
        //1.根据id获取对应的用户
        //2.根据用户id获取对应的课程
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
