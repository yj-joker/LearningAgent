package com.yjjoker.learningagent.harness;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.harness.context.ContextManager;
import com.yjjoker.learningagent.harness.llm.LlmClient;
import com.yjjoker.learningagent.harness.memory.ConversationMemoryService;
import com.yjjoker.learningagent.harness.tool.ToolRegistry;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentHarnessSessionDeletionTest {

    private static final Long USER_ID = 20L;

    @AfterEach
    void clearUserContext() {
        BaseContext.removeCurrentId();
    }

    @Test
    void shouldSoftDeleteOwnedSessionWithoutDeletingMessages() {
        BaseContext.setCurrentId(USER_ID);
        LearningSession session = new LearningSession();
        session.setId(10L);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);

        LearningSessionRepository repository = mock(LearningSessionRepository.class);
        when(repository.findSessionById(10L)).thenReturn(Optional.of(session));
        when(repository.updateSession(any())).thenReturn(1);

        AgentHarnessService service = AgentHarnessTestFactory.create(
                mock(LlmClient.class),
                new ToolRegistry(List.of()),
                List.of(),
                mock(ConversationMemoryService.class),
                repository
        );

        service.deleteSession(10L);

        assertEquals(LearningSessionStatusEnum.CANCELED, session.getStatus());
        verify(repository).updateSession(session);
    }
}
