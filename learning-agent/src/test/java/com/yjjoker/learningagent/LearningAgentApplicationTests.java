package com.yjjoker.learningagent;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.repository.test.impl.LearningSessionRepositoryTestImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Slf4j
class LearningAgentApplicationTests {

    @Test
    void createLearningSessionTest() {
        LearningSessionRepositoryTestImpl learningSessionRepositoryTestImpl =
                new LearningSessionRepositoryTestImpl(List.of(1001L,1002L,1003L));
        LearningSessionDTO learningSessionDTO_1 = new LearningSessionDTO();
        //测试是否成功创建一个会话
        learningSessionDTO_1.setCourseId(1003L);
        learningSessionDTO_1.setUserId(1000L);
        learningSessionRepositoryTestImpl.createSession(learningSessionDTO_1);
        //测试没有对应课程id时是否会创建失败
        LearningSessionDTO learningSessionDTO_2 = new LearningSessionDTO();
        learningSessionDTO_2.setCourseId(1009L);
        learningSessionDTO_2.setUserId(1000L);
        learningSessionRepositoryTestImpl.createSession(learningSessionDTO_2);
    }

}
