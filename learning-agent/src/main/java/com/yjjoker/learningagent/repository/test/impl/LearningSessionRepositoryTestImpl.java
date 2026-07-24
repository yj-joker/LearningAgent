package com.yjjoker.learningagent.repository.test.impl;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;
@Slf4j
@Repository
public class LearningSessionRepositoryTestImpl implements LearningSessionRepository {
private static List<Long> courseIdList=new ArrayList<>();
private static final Map<Long,LearningSession> learningSessionMap=new HashMap<>();
public LearningSessionRepositoryTestImpl(List<Long> courseIds){
    courseIdList=courseIds;
}
    @Override
    public Optional<LearningSession> createSession(LearningSessionDTO learningSessionDTO) {
        boolean contains = courseIdList.contains(learningSessionDTO.getCourseId());
        if(!contains){
            throw new LearningAgentServiceException("课程不存在");
        }
        LearningSession learningSession = new LearningSession();
        learningSession.setCourseId(learningSessionDTO.getCourseId());
        learningSession.setUserId(learningSessionDTO.getUserId());
        learningSession.setStatus(LearningSessionStatusEnum.ACTIVE);
        learningSession.setCreatedAt(LocalDateTime.now());
        learningSession.setUpdatedAt(LocalDateTime.now());
        learningSessionMap.put(learningSessionDTO.getCourseId(),new LearningSession());
        log.info("创建学习会话成功");
        return Optional.of(learningSession);
    }

}
