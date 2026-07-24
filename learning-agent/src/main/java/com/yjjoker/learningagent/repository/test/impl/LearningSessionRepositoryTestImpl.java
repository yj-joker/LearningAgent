package com.yjjoker.learningagent.repository.test.impl;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.repository.LearningSessionRepository;

import java.util.List;
import java.util.Optional;

public class LearningSessionRepositoryTestImpl implements LearningSessionRepository {

    @Override
    public Optional<LearningSession> createSession(LearningSessionDTO learningSessionDTO) {

        return Optional.empty();
    }
}
