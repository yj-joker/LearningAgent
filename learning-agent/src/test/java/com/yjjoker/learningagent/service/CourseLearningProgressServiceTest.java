package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.entity.*;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.projectenum.*;
import com.yjjoker.learningagent.repository.*;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.CourseLearningProgressVO;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 验证四类课程变化和旧确认拒绝；真实数据库锁另由 HTTP 验收覆盖。
class CourseLearningProgressServiceTest {
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final CoursesRepository courses = mock(CoursesRepository.class);
    private final ChaptersRepository chapters = mock(ChaptersRepository.class);
    private final KnowledgePointsRepository points = mock(KnowledgePointsRepository.class);
    private final CourseLearningProgressRepository progress = mock(CourseLearningProgressRepository.class);
    private final CourseLearningProgressService service = new CourseLearningProgressService(sessions, courses, chapters, points, progress);
    private final List<CourseLearningPointProgress> stored = new ArrayList<>();
    private final List<KnowledgePoints> source = new ArrayList<>();

    // 构造一个已确认知识点，仓库更新模拟实际状态和版本变化。
    @BeforeEach
    void prepare() {
        BaseContext.setCurrentId(1L);
        LearningSession session = new LearningSession();
        session.setId(2L); session.setCourseId(3L); session.setUserId(1L); session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(2L)).thenReturn(Optional.of(session));
        when(sessions.findSessionForUpdate(2L)).thenReturn(Optional.of(session));
        Courses course = new Courses(); course.setId(3L); course.setUserId(1L); course.setCourseName("测试课程");
        course.setUpdatedAt(LocalDateTime.of(2026, 10, 8, 0, 0));
        when(courses.findCourseById(3L)).thenReturn(course);
        when(progress.lockCourse(3L)).thenReturn(3L);
        Chapters chapter = new Chapters(); chapter.setId(4L); chapter.setCourseId(3L); chapter.setSortOrder(1L); chapter.setTitle("基础");
        when(chapters.getChaptersByCourseId(3L)).thenReturn(List.of(chapter));
        source.add(point(5L, 1L, "原正文"));
        when(points.findByChapterId(4L)).thenAnswer(call -> List.copyOf(source));
        CourseLearningPointProgress old = new CourseLearningPointProgress();
        old.setSessionId(2L); old.setCourseId(3L); old.setChapterId(4L); old.setKnowledgePointId(5L);
        old.setChapterSortOrderSnapshot(1L); old.setKnowledgePointSortOrderSnapshot(1L);
        old.setChapterTitleSnapshot("基础"); old.setKnowledgePointNameSnapshot("知识点5");
        old.setKnowledgePointDescriptionSnapshot("原正文"); old.setCourseUpdatedAtSnapshot(course.getUpdatedAt());
        old.setStatus(CourseLearningPointStatus.CONFIRMED); old.setEvidenceSummary("旧学习证据"); old.setVersion(3L);
        stored.add(old);
        when(progress.findBySessionId(2L)).thenAnswer(call -> List.copyOf(stored));
        when(progress.findAllForUpdate(2L)).thenAnswer(call -> List.copyOf(stored));
        when(progress.insertIfAbsent(any())).thenAnswer(call -> { stored.add(call.getArgument(0)); return 1; });
        when(progress.updateSnapshot(any())).thenAnswer(call -> {
            CourseLearningPointProgress next = call.getArgument(0);
            CourseLearningPointProgress previous = stored.stream().filter(p -> p.getKnowledgePointId().equals(next.getKnowledgePointId())).findFirst().orElseThrow();
            next.setEvidenceSummary(previous.getEvidenceSummary()); next.setVersion(next.getVersion() + 1);
            stored.set(stored.indexOf(previous), next); return 1;
        });
    }

    // 线程复用不能继承测试身份。
    @AfterEach
    void cleanup() { BaseContext.removeCurrentId(); }

    // 新增项为未开始，旧确认保持，课程不再是已完成。
    @Test
    void addedPointReopensCompletedCourse() {
        source.add(point(6L, 2L, "新增正文"));
        CourseLearningProgressVO result = synchronize("ADDED");
        assertEquals(6L, result.getCurrentKnowledgePointId());
        assertFalse(result.isCompleted());
        assertEquals(CourseLearningPointStatus.CONFIRMED, stored.getFirst().getStatus());
        assertEquals(CourseLearningPointStatus.NOT_STARTED, stored.getLast().getStatus());
    }

    // 删除项留历史证据，不再成为当前知识点，空课程不宣称掌握。
    @Test
    void removedPointKeepsEvidenceButLeavesCursor() {
        source.clear();
        CourseLearningProgressVO result = synchronize("REMOVED");
        assertNull(result.getCurrentKnowledgePointId());
        assertFalse(result.isCompleted());
        assertEquals(CourseLearningPointStatus.REMOVED, stored.getFirst().getStatus());
        assertEquals("旧学习证据", stored.getFirst().getEvidenceSummary());
    }

    // 顺序变化不重置掌握状态，重复同步不再增加版本。
    @Test
    void reorderedPointPreservesConfirmation() {
        source.getFirst().setSortOrder(9L);
        CourseLearningProgressVO result = synchronize("REORDERED");
        assertTrue(result.isCompleted());
        assertEquals(CourseLearningPointStatus.CONFIRMED, stored.getFirst().getStatus());
        assertEquals(9L, stored.getFirst().getKnowledgePointSortOrderSnapshot());
        service.sync(2L, result.getSyncToken());
        assertEquals(4L, stored.getFirst().getVersion());
    }

    // 新正文使旧确认变为待复核，但证据没有被删除。
    @Test
    void changedContentRequiresReview() {
        source.getFirst().setDescription("新正文");
        CourseLearningProgressVO result = synchronize("CONTENT_CHANGED");
        assertEquals(5L, result.getCurrentKnowledgePointId());
        assertEquals(CourseLearningPointStatus.REVIEW_REQUIRED, stored.getFirst().getStatus());
        assertEquals("旧学习证据", stored.getFirst().getEvidenceSummary());
        assertEquals("新正文", stored.getFirst().getKnowledgePointDescriptionSnapshot());
    }

    // 用户看到变化后源内容又变了，旧凭据不能修改数据库。
    @Test
    void rejectsStaleSourceToken() {
        String token = service.load(2L).getSyncToken();
        source.getFirst().setDescription("第二次修改");
        assertThrows(ClientDataErrorException.class, () -> service.sync(2L, token));
        verify(progress, never()).updateSnapshot(any());
    }

    // 进度版本变化也使旧确认失效，不仅校验源课程。
    @Test
    void rejectsStaleProgressToken() {
        String token = service.load(2L).getSyncToken();
        stored.getFirst().setVersion(4L);
        assertThrows(ClientDataErrorException.class, () -> service.sync(2L, token));
    }

    // 审批等待期间同步了正文，旧进度申请不能覆盖待复核状态。
    @Test
    void rejectsApprovedProposalFromBeforeSync() {
        stored.getFirst().setStatus(CourseLearningPointStatus.IN_PROGRESS);
        CourseLearningProgressVO original = service.load(2L);
        source.getFirst().setDescription("变化后的正文");
        service.sync(2L, service.load(2L).getSyncToken());
        var proposal = proposal(3L, CourseLearningPointStatus.CONFIRMED);
        assertThrows(ClientDataErrorException.class,
                () -> service.applyApprovedProposal(2L, original, proposal, "用户答案"));
        verify(progress, never()).updateIfVersionMatches(any(), any(), any());
        assertEquals(CourseLearningPointStatus.REVIEW_REQUIRED, stored.getFirst().getStatus());
    }

    // 待复核可以重新申请开始，但不能使用旧证据直接跳到已确认。
    @Test
    void reviewMustStartAgainBeforeConfirmation() {
        source.getFirst().setDescription("新正文");
        service.sync(2L, service.load(2L).getSyncToken());
        CourseLearningProgressVO snapshot = service.load(2L);
        assertTrue(service.validateProposal(2L, snapshot,
                proposal(4L, CourseLearningPointStatus.IN_PROGRESS), "用户答案").isSuccess());
        assertFalse(service.validateProposal(2L, snapshot,
                proposal(4L, CourseLearningPointStatus.CONFIRMED), "用户答案").isSuccess());
    }

    // 构造当前知识点申请，证据必须来自本轮用户消息。
    private com.yjjoker.learningagent.harness.course.model.CourseProgressProposal proposal(Long version, CourseLearningPointStatus status) {
        var proposal = new com.yjjoker.learningagent.harness.course.model.CourseProgressProposal();
        proposal.setPointRef("point-5"); proposal.setExpectedVersion(version); proposal.setTargetStatus(status);
        proposal.setEvidenceType(LearningEvidenceType.BOTH); proposal.setEvidenceSummary("解释和练习");
        proposal.setAssessmentReason("用户提交了答案"); proposal.setUserEvidence("用户答案");
        return proposal;
    }

    // 同步后变化清单清空；读取时不能在后台先改了用户进度。
    private CourseLearningProgressVO synchronize(String type) {
        CourseLearningProgressVO before = service.load(2L);
        assertEquals(type, before.getContentChanges().getFirst().getType());
        assertEquals(3L, stored.getFirst().getVersion());
        CourseLearningProgressVO after = service.sync(2L, before.getSyncToken());
        assertFalse(after.isCourseContentChanged());
        assertTrue(after.getContentChanges().isEmpty());
        return after;
    }

    // 构造相同课程归属的知识点，变化只发生在被测试字段上。
    private KnowledgePoints point(Long id, Long order, String body) {
        KnowledgePoints point = new KnowledgePoints(); point.setId(id); point.setCourseId(3L); point.setChapterId(4L);
        point.setName("知识点" + id); point.setSortOrder(order); point.setDescription(body); return point;
    }
}
