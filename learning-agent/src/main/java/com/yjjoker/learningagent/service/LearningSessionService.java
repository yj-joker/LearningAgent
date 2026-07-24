package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.vo.LearningSessionVO;

public interface LearningSessionService {
    LearningSessionVO createSession(LearningSessionDTO learningSessionDTO);
}
