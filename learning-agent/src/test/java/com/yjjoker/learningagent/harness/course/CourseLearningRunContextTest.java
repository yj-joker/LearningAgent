package com.yjjoker.learningagent.harness.course;

import com.yjjoker.learningagent.projectenum.CourseLearningPointStatus;
import com.yjjoker.learningagent.projectenum.CourseLearningStatus;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.CourseLearningPointProgressVO;
import com.yjjoker.learningagent.vo.CourseLearningProgressVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// 只测试课程快照边界，不访问数据库和真实模型。
class CourseLearningRunContextTest {
    // 线程身份清理后，其他测试不会继承本次课程权限。
    @AfterEach
    void clearIdentity() { BaseContext.removeCurrentId(); }

    // 模型视图同时包含全部索引和当前知识点正文，空证据字段也不能导致序列化失败。
    @Test
    void buildsIndexAndCurrentPointView() {
        BaseContext.setCurrentId(7L);
        CourseLearningPointProgressVO point = new CourseLearningPointProgressVO();
        point.setKnowledgePointId(10L);
        point.setChapterTitle("集合");
        point.setKnowledgePointName("List");
        point.setKnowledgePointDescription(null);
        point.setStatus(CourseLearningPointStatus.NOT_STARTED);
        point.setVersion(1L);
        CourseLearningProgressVO progress = new CourseLearningProgressVO(3L, 8L, "Java",
                null, CourseLearningStatus.NOT_STARTED, 2L, 10L, false, false, List.of(point));

        CourseLearningRunContext context = new CourseLearningRunContext();
        context.bind(progress);
        String view = context.modelView();

        assertTrue(view.contains("point-10"));
        assertTrue(view.contains("List"));
        assertTrue(view.contains("currentPoint"));
        context.clear();
        assertThrows(SecurityException.class, context::require);
    }
}
