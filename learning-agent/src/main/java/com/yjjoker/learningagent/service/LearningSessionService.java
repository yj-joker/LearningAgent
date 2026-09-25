package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.vo.LearningSessionVO;

import java.util.List;

public interface LearningSessionService {
    LearningSessionVO createSession(LearningSessionDTO learningSessionDTO);
    LearningSessionVO changeSessionStatus(Long sessionId);

    // 返回给用户展示的消息，不包含工具调用和工具结果。
    List<LearningSessionMessage> findSessionMessages(Long sessionId);
}
