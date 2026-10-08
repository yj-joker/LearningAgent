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

    // 移除项不进入索引，其他知识点不发送正文，当前待复核项带新正文和旧证据。
    @Test
    void excludesRemovedAndOnlyIncludesCurrentBody() {
        BaseContext.setCurrentId(7L);
        CourseLearningPointProgressVO current = new CourseLearningPointProgressVO();
        current.setKnowledgePointId(10L); current.setKnowledgePointName("GET");
        current.setKnowledgePointDescription("新正文"); current.setEvidenceSummary("旧证据");
        current.setStatus(CourseLearningPointStatus.REVIEW_REQUIRED); current.setVersion(4L);
        CourseLearningPointProgressVO next = new CourseLearningPointProgressVO();
        next.setKnowledgePointId(11L); next.setKnowledgePointName("POST");
        next.setKnowledgePointDescription("不应提前发送的正文"); next.setStatus(CourseLearningPointStatus.NOT_STARTED);
        CourseLearningPointProgressVO removed = new CourseLearningPointProgressVO();
        removed.setKnowledgePointId(12L); removed.setStatus(CourseLearningPointStatus.REMOVED);
        CourseLearningProgressVO progress = new CourseLearningProgressVO(3L, 8L, "HTTP", null,
                CourseLearningStatus.IN_PROGRESS, 2L, 10L, false, false, List.of(current, next, removed));
        CourseLearningRunContext context = new CourseLearningRunContext();
        context.bind(progress);
        try {
            String view = context.modelView();
            assertTrue(view.contains("新正文"));
            assertTrue(view.contains("旧证据"));
            assertTrue(view.contains("REVIEW_REQUIRED"));
            assertTrue(view.contains("point-11"));
            assertFalse(view.contains("不应提前发送的正文"));
            assertFalse(view.contains("point-12"));
        } finally {
            context.clear();
        }
    }
}
