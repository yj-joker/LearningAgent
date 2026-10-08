package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.entity.Chapters;
import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.entity.CourseLearningPointProgress;
import com.yjjoker.learningagent.entity.KnowledgePoints;
import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.projectenum.CourseLearningPointStatus;
import com.yjjoker.learningagent.projectenum.CourseLearningStatus;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.ChaptersRepository;
import com.yjjoker.learningagent.repository.CourseLearningProgressRepository;
import com.yjjoker.learningagent.repository.CoursesRepository;
import com.yjjoker.learningagent.repository.KnowledgePointsRepository;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.harness.course.model.CourseProgressProposal;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.projectenum.LearningEvidenceType;
import com.yjjoker.learningagent.vo.CourseLearningPointProgressVO;
import com.yjjoker.learningagent.vo.CourseLearningProgressVO;
import com.yjjoker.learningagent.vo.CourseContentChangeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

// 管理课程快照和学习进度；读取只检测变化，用户确认后才同步。
@Service
@RequiredArgsConstructor
@Slf4j
public class CourseLearningProgressService {
    private static final JsonMapper JSON = new JsonMapper();
    private final LearningSessionRepository sessionRepository;
    private final CoursesRepository coursesRepository;
    private final ChaptersRepository chaptersRepository;
    private final KnowledgePointsRepository knowledgePointsRepository;
    private final CourseLearningProgressRepository progressRepository;

    // 显式开始课程学习；重复请求只返回已有快照，不重置证据、不自动加入新知识点。
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public CourseLearningProgressVO initialize(Long sessionId) {
        requireSessionId(sessionId);
        Long userId = requireUser();
        // 会话行锁让同一会话的两次初始化串行，避免保存不同课程版本的混合快照。
        LearningSession session = sessionRepository.findSessionForUpdate(sessionId)
                .orElseThrow(() -> new NotFountException("学习会话不存在"));
        validateSession(session, userId);
        if (session.getStatus() != LearningSessionStatusEnum.ACTIVE) {
            throw new ClientDataErrorException("只有进行中的会话可以开始课程学习");
        }
        lockSource(session.getCourseId());
        Courses course = requireCourse(session.getCourseId(), userId);
        List<CourseLearningPointProgress> stored = progressRepository.findBySessionId(sessionId);
        if (stored.isEmpty()) {
            // 同一事务读取完整课程结构，失败时所有进度插入一起回滚。
            List<CourseLearningPointProgress> snapshot = buildStructure(sessionId, course, true);
            for (CourseLearningPointProgress point : snapshot) {
                progressRepository.insertIfAbsent(point);
            }
            stored = progressRepository.findBySessionId(sessionId);
            if (stored.size() != snapshot.size()) {
                throw new IllegalStateException("课程进度初始化数量不一致");
            }
            log.info("课程进度初始化完成，等待事务提交，userId={}，sessionId={}，courseId={}，pointCount={}",
                    userId, sessionId, course.getId(), stored.size());
        } else {
            log.info("课程进度已初始化，保留原快照，userId={}，sessionId={}，pointCount={}",
                    userId, sessionId, stored.size());
        }
        return buildView(sessionId, course, stored);
    }

    // 只读已有进度；未初始化时要求显式开始，GET 不产生数据库写入。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CourseLearningProgressVO load(Long sessionId) {
        requireSessionId(sessionId);
        Long userId = requireUser();
        LearningSession session = sessionRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFountException("学习会话不存在"));
        validateSession(session, userId);
        Courses course = requireCourse(session.getCourseId(), userId);
        List<CourseLearningPointProgress> stored = progressRepository.findBySessionId(sessionId);
        if (stored.isEmpty()) {
            throw new ClientDataErrorException("尚未开始课程学习，请先初始化课程进度");
        }
        return buildView(sessionId, course, stored);
    }

    // 用户确认后在短事务内同步；新增、移除、重排和正文修改一起提交或一起回滚。
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public CourseLearningProgressVO sync(Long sessionId, String expectedSyncToken) {
        requireSessionId(sessionId);
        Long userId = requireUser();
        LearningSession session = sessionRepository.findSessionForUpdate(sessionId)
                .orElseThrow(() -> new NotFountException("学习会话不存在"));
        validateSession(session, userId);
        if (session.getStatus() != LearningSessionStatusEnum.ACTIVE) {
            throw new ClientDataErrorException("会话已结束，不能同步课程内容");
        }
        // 先锁源内容和进度，再读取快照；数据库锁只覆盖校验和写入，不占用模型调用时间。
        lockSource(session.getCourseId());
        Courses course = requireCourse(session.getCourseId(), userId);
        List<CourseLearningPointProgress> stored = progressRepository.findAllForUpdate(sessionId);
        if (stored.isEmpty()) throw new ClientDataErrorException("请先初始化课程学习进度");
        List<CourseLearningPointProgress> latest = buildStructure(sessionId, course, false);
        if (!Objects.equals(expectedSyncToken, syncToken(stored, latest))) {
            log.warn("课程同步被拒绝，sessionId={}，courseId={}，原因=内容或进度版本已变化", sessionId, course.getId());
            throw new ClientDataErrorException("课程内容或进度已变化，请重新查看变化后确认同步");
        }
        List<CourseContentChangeVO> changes = detectChanges(stored, latest);
        if (changes.isEmpty()) return buildView(sessionId, course, stored);
        Map<Long, CourseLearningPointProgress> previous = new HashMap<>();
        for (CourseLearningPointProgress point : stored) previous.put(point.getKnowledgePointId(), point);
        for (CourseLearningPointProgress point : latest) {
            CourseLearningPointProgress old = previous.remove(point.getKnowledgePointId());
            if (old == null) {
                // 新内容不能继承其他知识点或原课程的已完成状态。
                progressRepository.insertIfAbsent(point);
                continue;
            }
            point.setVersion(old.getVersion());
            // 已学习的正文或归属变了，保留证据但要求复核；未开始的仍是未开始。
            boolean requiresReview = old.getStatus() == CourseLearningPointStatus.REMOVED
                    || (contentDiffers(old, point) && old.getStatus() != CourseLearningPointStatus.NOT_STARTED);
            point.setStatus(requiresReview ? CourseLearningPointStatus.REVIEW_REQUIRED : old.getStatus());
            requireSnapshotUpdated(point);
        }
        for (CourseLearningPointProgress removed : previous.values()) {
            // 移除不是物理删除，旧正文和旧学习证据仍可供查看。
            if (removed.getStatus() != CourseLearningPointStatus.REMOVED) {
                removed.setStatus(CourseLearningPointStatus.REMOVED);
                requireSnapshotUpdated(removed);
            }
        }
        CourseLearningProgressVO updated = buildView(sessionId, course, progressRepository.findBySessionId(sessionId));
        // 只记录类型和数量，不在日志中输出课程正文或用户学习答案。
        Map<String, Long> counts = changes.stream().collect(java.util.stream.Collectors.groupingBy(
                CourseContentChangeVO::getType, java.util.stream.Collectors.counting()));
        log.info("课程内容同步完成，等待事务提交，sessionId={}，courseId={}，changes={}，currentPointId={}",
                sessionId, course.getId(), counts, updated.getCurrentKnowledgePointId());
        return updated;
    }

    // 按固定顺序锁源表，保护检查到写入之间的课程内容和新增范围。
    private void lockSource(Long courseId) {
        if (progressRepository.lockCourse(courseId) == null) throw new NotFountException("课程不存在");
        progressRepository.lockChapters(courseId);
        progressRepository.lockKnowledgePoints(courseId);
    }

    // 更新失败就回滚整个同步，不留下只更新了一部分的课程。
    private void requireSnapshotUpdated(CourseLearningPointProgress point) {
        if (progressRepository.updateSnapshot(point) != 1) {
            throw new ClientDataErrorException("课程进度版本已变化，请重新读取");
        }
    }

    // 审批前只读最新数据；参数或版本失效时返回可修正结果，不创建无效审批。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToolExecutionResult validateProposal(Long sessionId, CourseLearningProgressVO snapshot,
                                                CourseProgressProposal proposal, String userMessage) {
        try {
            CourseLearningProgressVO latest = load(sessionId);
            validateProposalAgainstView(sessionId, snapshot, proposal, userMessage, latest);
            log.info("课程进度审批预检通过，sessionId={}，pointRef={}，targetStatus={}，version={}",
                    sessionId, proposal.getPointRef(), proposal.getTargetStatus(), proposal.getExpectedVersion());
            return ToolExecutionResult.success("课程进度申请已校验，等待用户审批；尚未改变进度");
        } catch (ClientDataErrorException | IllegalArgumentException exception) {
            log.warn("课程进度审批预检失败，sessionId={}，errorType={}", sessionId, exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("INVALID_COURSE_PROGRESS", exception.getMessage(), true);
        }
    }

    // 用户批准后锁住会话和全部进度；版本校验、证据保存和状态更新在同一短事务内完成。
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ToolExecutionResult applyApprovedProposal(Long sessionId, CourseLearningProgressVO snapshot,
                                                     CourseProgressProposal proposal, String userMessage) {
        requireSessionId(sessionId);
        Long userId = requireUser();
        // 先锁会话，再锁进度；同一会话的初始化、更新和删除不能穿插到写入中途。
        LearningSession session = sessionRepository.findSessionForUpdate(sessionId)
                .orElseThrow(() -> new NotFountException("学习会话不存在"));
        validateSession(session, userId);
        if (session.getStatus() != LearningSessionStatusEnum.ACTIVE) {
            throw new ClientDataErrorException("会话已结束，不能修改课程进度");
        }
        lockSource(session.getCourseId());
        Courses course = requireCourse(session.getCourseId(), userId);
        List<CourseLearningPointProgress> stored = progressRepository.findAllForUpdate(sessionId);
        CourseLearningProgressVO latest = buildView(sessionId, course, stored);
        // 审批不是跳过校验的凭据；等待期间版本或当前知识点变化必须重新申请。
        Long pointId = validateProposalAgainstView(sessionId, snapshot, proposal, userMessage, latest);
        if (progressRepository.updateIfVersionMatches(sessionId, pointId, proposal) != 1) {
            throw new ClientDataErrorException("课程知识点进度已变化，请重新读取后申请");
        }
        // 重新读取事务内的新状态，当前知识点始终由第一个未确认项推导。
        CourseLearningProgressVO updated = buildView(sessionId, course, progressRepository.findBySessionId(sessionId));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("updated", true);
        result.put("pointRef", proposal.getPointRef());
        result.put("status", proposal.getTargetStatus());
        result.put("version", proposal.getExpectedVersion() + 1);
        result.put("currentPointRef", updated.getCurrentKnowledgePointId() == null
                ? null : "point-" + updated.getCurrentKnowledgePointId());
        result.put("completed", updated.isCompleted());
        log.info("课程进度已写入，等待事务提交，sessionId={}，pointRef={}，status={}，version={}->{}，nextPointId={}，completed={}",
                sessionId, proposal.getPointRef(), proposal.getTargetStatus(), proposal.getExpectedVersion(),
                proposal.getExpectedVersion() + 1, updated.getCurrentKnowledgePointId(), updated.isCompleted());
        return ToolExecutionResult.success(JSON.writeValueAsString(result));
    }

    // 同时校验本轮范围和最新数据库事实；学习证据是否足够由模型判断，最终由用户审批。
    private Long validateProposalAgainstView(Long sessionId, CourseLearningProgressVO snapshot,
                                             CourseProgressProposal proposal, String userMessage,
                                             CourseLearningProgressVO latest) {
        if (snapshot == null || proposal == null || !Objects.equals(snapshot.getSessionId(), sessionId)
                || !Objects.equals(snapshot.getCourseId(), latest.getCourseId())) {
            throw new ClientDataErrorException("课程进度申请范围已失效");
        }
        // 源课程发生变化时先由用户确认同步，不能凭旧正文申请新课程的掌握进度。
        if (latest.isCourseContentChanged()) {
            throw new ClientDataErrorException("课程内容已变化，请先查看变化并确认同步");
        }
        // 引用必须逐字匹配索引，不解析或修正模型猜测的雪花编号。
        CourseLearningPointProgressVO point = latest.getPoints().stream()
                .filter(item -> ("point-" + item.getKnowledgePointId()).equals(proposal.getPointRef()))
                .findFirst().orElseThrow(() -> new ClientDataErrorException("知识点引用不属于当前课程"));
        if (!Objects.equals(point.getKnowledgePointId(), latest.getCurrentKnowledgePointId())
                || !Objects.equals(point.getKnowledgePointId(), snapshot.getCurrentKnowledgePointId())) {
            throw new ClientDataErrorException("只能申请当前知识点的进度，不能跳过前面的未确认项");
        }
        CourseLearningPointProgressVO original = snapshot.getPoints().stream()
                .filter(item -> Objects.equals(item.getKnowledgePointId(), point.getKnowledgePointId()))
                .findFirst().orElseThrow(() -> new ClientDataErrorException("本轮课程快照缺少目标知识点"));
        if (proposal.getExpectedVersion() == null || !Objects.equals(point.getVersion(), proposal.getExpectedVersion())
                || !Objects.equals(original.getVersion(), proposal.getExpectedVersion())) {
            throw new ClientDataErrorException("知识点进度版本已变化，请重新读取课程进度");
        }
        // 只允许前进，不允许旧申请覆盖已经确认的状态。
        if (proposal.getTargetStatus() == CourseLearningPointStatus.IN_PROGRESS) {
            if (point.getStatus() != CourseLearningPointStatus.NOT_STARTED
                    && point.getStatus() != CourseLearningPointStatus.REVIEW_REQUIRED) {
                throw new ClientDataErrorException("只有未开始或待复核的知识点才能申请开始学习");
            }
        } else if (proposal.getTargetStatus() == CourseLearningPointStatus.CONFIRMED) {
            if (point.getStatus() != CourseLearningPointStatus.IN_PROGRESS) {
                throw new ClientDataErrorException("必须先开始学习，才能申请确认掌握");
            }
            if (proposal.getEvidenceType() != LearningEvidenceType.BOTH) {
                throw new ClientDataErrorException("确认掌握必须同时具备解释和独立练习证据");
            }
        } else {
            throw new ClientDataErrorException("课程进度只能申请 IN_PROGRESS 或 CONFIRMED");
        }
        // 用户原话必须来自当前消息，不能把助手讲解当作用户掌握的证据。
        if (proposal.getEvidenceType() == null || proposal.getEvidenceSummary() == null
                || proposal.getEvidenceSummary().isBlank() || proposal.getAssessmentReason() == null
                || proposal.getAssessmentReason().isBlank() || proposal.getUserEvidence() == null
                || proposal.getUserEvidence().isBlank() || userMessage == null
                || !userMessage.contains(proposal.getUserEvidence())) {
            throw new ClientDataErrorException("进度申请必须包含证据摘要、理由和本轮用户的真实原话");
        }
        return point.getKnowledgePointId();
    }

    // 从已确认同步的顺序推导当前知识点，已移除记录不参与推进。
    private CourseLearningProgressVO buildView(Long sessionId, Courses course,
                                               List<CourseLearningPointProgress> stored) {
        List<CourseLearningPointProgress> ordered = new ArrayList<>(stored);
        ordered.sort(Comparator.comparing(CourseLearningPointProgress::getChapterSortOrderSnapshot)
                .thenComparing(CourseLearningPointProgress::getChapterId)
                .thenComparing(CourseLearningPointProgress::getKnowledgePointSortOrderSnapshot)
                .thenComparing(CourseLearningPointProgress::getKnowledgePointId));
        for (CourseLearningPointProgress point : ordered) {
            // 数据归属异常时中止，不把其他课程的记录混入当前课程。
            if (!Objects.equals(point.getCourseId(), course.getId())
                    || !Objects.equals(point.getSessionId(), sessionId)) {
                throw new IllegalStateException("课程进度记录归属不一致");
            }
        }
        List<CourseLearningPointProgress> active = ordered.stream()
                .filter(point -> point.getStatus() != CourseLearningPointStatus.REMOVED).toList();
        CourseLearningPointProgress current = active.stream()
                .filter(point -> point.getStatus() != CourseLearningPointStatus.CONFIRMED)
                .findFirst().orElse(null);
        boolean completed = !active.isEmpty() && current == null;
        List<CourseLearningPointProgress> latest = buildStructure(sessionId, course, false);
        List<CourseContentChangeVO> changes = detectChanges(ordered, latest);
        boolean changed = !changes.isEmpty();
        // 旧快照完成不等于新版课程完成；等待同步时不宣称整门课程已完成。
        completed = completed && !changed;
        CourseLearningStatus status = completed ? CourseLearningStatus.COMPLETED
                : active.stream().allMatch(point -> point.getStatus() == CourseLearningPointStatus.NOT_STARTED)
                ? CourseLearningStatus.NOT_STARTED : CourseLearningStatus.IN_PROGRESS;
        List<CourseLearningPointProgressVO> points = ordered.stream()
                .map(CourseLearningPointProgressVO::new).toList();
        log.info("读取课程学习进度，sessionId={}，courseId={}，pointCount={}，currentPointId={}，completed={}，contentChanged={}",
                sessionId, course.getId(), points.size(), current == null ? null : current.getKnowledgePointId(),
                completed, changed);
        // 课程仍有未同步变化时不宣称完成，所有进度来自数据库而不是模型回答。
        CourseLearningProgressVO view = new CourseLearningProgressVO(sessionId, course.getId(), course.getCourseName(),
                course.getUpdatedAt(), status, current == null ? null : current.getChapterId(),
                current == null ? null : current.getKnowledgePointId(), completed, changed, points);
        view.setContentChanges(changes);
        view.setSyncToken(syncToken(ordered, latest));
        return view;
    }

    // 按章节和知识点展示顺序生成快照，完整保存描述，不截断课程正文。
    private List<CourseLearningPointProgress> buildStructure(Long sessionId, Courses course, boolean initializing) {
        List<CourseLearningPointProgress> result = new ArrayList<>();
        List<Chapters> chapters = new ArrayList<>(chaptersRepository.getChaptersByCourseId(course.getId()));
        chapters.sort(Comparator.comparing(Chapters::getSortOrder).thenComparing(Chapters::getId));
        LocalDateTime now = LocalDateTime.now();
        for (Chapters chapter : chapters) {
            List<KnowledgePoints> points = new ArrayList<>(knowledgePointsRepository.findByChapterId(chapter.getId()));
            if (initializing && points.isEmpty()) {
                throw new ClientDataErrorException("课程存在空章节，请先补充章节知识点");
            }
            points.sort(Comparator.comparing(KnowledgePoints::getSortOrder).thenComparing(KnowledgePoints::getId));
            for (KnowledgePoints point : points) {
                if (!Objects.equals(point.getCourseId(), course.getId())) {
                    throw new IllegalStateException("知识点不属于当前课程");
                }
                CourseLearningPointProgress progress = new CourseLearningPointProgress();
                progress.setSessionId(sessionId);
                progress.setCourseId(course.getId());
                progress.setChapterId(chapter.getId());
                progress.setKnowledgePointId(point.getId());
                progress.setChapterSortOrderSnapshot(chapter.getSortOrder());
                progress.setKnowledgePointSortOrderSnapshot(point.getSortOrder());
                progress.setCourseUpdatedAtSnapshot(course.getUpdatedAt());
                progress.setChapterTitleSnapshot(chapter.getTitle());
                progress.setKnowledgePointNameSnapshot(point.getName());
                progress.setKnowledgePointDescriptionSnapshot(point.getDescription());
                progress.setStatus(CourseLearningPointStatus.NOT_STARTED);
                progress.setVersion(1L);
                progress.setCreatedAt(now);
                progress.setUpdatedAt(now);
                result.add(progress);
            }
        }
        if (initializing && result.isEmpty()) {
            throw new ClientDataErrorException("课程没有知识点，不能开始课程学习");
        }
        return result;
    }

    // 比较真实内容和顺序；章节或知识点编辑不一定更新课程表时间，不能只看时间戳。
    private List<CourseContentChangeVO> detectChanges(List<CourseLearningPointProgress> stored,
                                                    List<CourseLearningPointProgress> latest) {
        List<CourseContentChangeVO> changes = new ArrayList<>();
        Map<Long, CourseLearningPointProgress> latestById = new HashMap<>();
        for (CourseLearningPointProgress point : latest) {
            latestById.put(point.getKnowledgePointId(), point);
        }
        for (CourseLearningPointProgress point : stored) {
            CourseLearningPointProgress now = latestById.remove(point.getKnowledgePointId());
            if (now == null) {
                if (point.getStatus() != CourseLearningPointStatus.REMOVED) {
                    changes.add(new CourseContentChangeVO("REMOVED", point.getKnowledgePointId(), point.getKnowledgePointNameSnapshot()));
                }
                continue;
            }
            if (point.getStatus() == CourseLearningPointStatus.REMOVED) {
                changes.add(new CourseContentChangeVO("ADDED", now.getKnowledgePointId(), now.getKnowledgePointNameSnapshot()));
                continue;
            }
            if (contentDiffers(point, now)) {
                changes.add(new CourseContentChangeVO("CONTENT_CHANGED", now.getKnowledgePointId(), now.getKnowledgePointNameSnapshot()));
            }
            if (!Objects.equals(point.getChapterSortOrderSnapshot(), now.getChapterSortOrderSnapshot())
                    || !Objects.equals(point.getKnowledgePointSortOrderSnapshot(), now.getKnowledgePointSortOrderSnapshot())) {
                changes.add(new CourseContentChangeVO("REORDERED", now.getKnowledgePointId(), now.getKnowledgePointNameSnapshot()));
            }
        }
        for (CourseLearningPointProgress added : latestById.values()) {
            changes.add(new CourseContentChangeVO("ADDED", added.getKnowledgePointId(), added.getKnowledgePointNameSnapshot()));
        }
        changes.sort(Comparator.comparing(CourseContentChangeVO::getKnowledgePointId).thenComparing(CourseContentChangeVO::getType));
        return List.copyOf(changes);
    }

    // 比较教学内容与归属，不把更新时间的变化误认为知识发生变化。
    private boolean contentDiffers(CourseLearningPointProgress old, CourseLearningPointProgress latest) {
        return !Objects.equals(old.getChapterId(), latest.getChapterId())
                || !Objects.equals(old.getChapterTitleSnapshot(), latest.getChapterTitleSnapshot())
                || !Objects.equals(old.getKnowledgePointNameSnapshot(), latest.getKnowledgePointNameSnapshot())
                || !Objects.equals(old.getKnowledgePointDescriptionSnapshot(), latest.getKnowledgePointDescriptionSnapshot());
    }

    // 对确定顺序的两份快照计算摘要，绑定用户看到的源内容和所有进度版本。
    private String syncToken(List<CourseLearningPointProgress> stored, List<CourseLearningPointProgress> latest) {
        Comparator<CourseLearningPointProgress> order = Comparator.comparing(CourseLearningPointProgress::getKnowledgePointId);
        // 源结构生成时间不是课程版本，不参与凭据；也不修改调用方的快照对象。
        List<List<Object>> source = latest.stream().sorted(order).map(point -> java.util.Arrays.<Object>asList(
                point.getCourseId(), point.getChapterId(), point.getKnowledgePointId(),
                point.getChapterSortOrderSnapshot(), point.getKnowledgePointSortOrderSnapshot(),
                point.getCourseUpdatedAtSnapshot(), point.getChapterTitleSnapshot(),
                point.getKnowledgePointNameSnapshot(), point.getKnowledgePointDescriptionSnapshot())).toList();
        String content = JSON.writeValueAsString(List.of(stored.stream().sorted(order).toList(), source));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算课程同步凭据", exception);
        }
    }

    // 验证会话归属和假删除状态，防止猜测会话编号读取其他用户的进度。
    private void validateSession(LearningSession session, Long userId) {
        if (!Objects.equals(session.getUserId(), userId)) {
            log.warn("课程进度访问被拒绝，userId={}，sessionId={}", userId, session.getId());
            throw new ClientDataErrorException("无权查看该学习会话课程进度");
        }
        if (session.getStatus() == LearningSessionStatusEnum.CANCELED) {
            throw new ClientDataErrorException("学习会话已删除");
        }
    }

    // 每次读取都重新校验课程可见性，下架后的私有课程不向其他用户开放。
    private Courses requireCourse(Long courseId, Long userId) {
        Courses course = coursesRepository.findCourseById(courseId);
        if (course == null) throw new NotFountException("课程不存在");
        if (!Objects.equals(course.getUserId(), userId) && course.getCourseType() != CoursesTypeEnum.PUBLISHED) {
            throw new ClientDataErrorException("无权学习该课程");
        }
        return course;
    }

    // 服务调用也校验会话编号，不能只依赖 HTTP 参数校验。
    private void requireSessionId(Long sessionId) {
        if (sessionId == null || sessionId <= 0) throw new ClientDataErrorException("学习会话 ID 不合法");
    }

    // 登录身份来自后端上下文，不接受请求伪造用户 ID。
    private Long requireUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) throw new ClientDataErrorException("请先登录");
        return userId;
    }
}
