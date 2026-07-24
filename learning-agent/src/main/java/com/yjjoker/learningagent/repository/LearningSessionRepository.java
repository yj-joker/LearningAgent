package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.entity.LearningSession;

import java.util.Optional;

public interface LearningSessionRepository {
   Optional<LearningSession> createSession(LearningSessionDTO learningSessionDTO);
}
