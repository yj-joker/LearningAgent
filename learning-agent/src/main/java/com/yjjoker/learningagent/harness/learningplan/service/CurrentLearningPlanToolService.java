package com.yjjoker.learningagent.harness.learningplan.service;

import com.yjjoker.learningagent.entity.LearningPlanDraft;
import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalContext;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalService;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.service.LearningPlanProgressService;
import com.yjjoker.learningagent.vo.LearningPlanProgressVO;
import com.yjjoker.learningagent.vo.LearningPlanStepProgressVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import com.yjjoker.learningagent.projectenum.LearningPlanStepProgressStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 读取当前专注会话绑定的最新 ACTIVE 学习计划，并合并每个阶段的真实进度。
@Service
@RequiredArgsConstructor
@Slf4j
public class CurrentLearningPlanToolService {
    private static final JsonMapper JSON = new JsonMapper();

    private final SessionGoalContext sessionGoalContext;
    private final SessionGoalService sessionGoalService;
    private final LearningPlanProgressService progressService;

    // 从当前会话解析计划绑定关系，不接受模型传入 draftRef。
    public ToolExecutionResult get() {
        try {
            Long sessionId = sessionGoalContext.require().getState().getSessionId();
            LearningPlanDraft draft = sessionGoalService.loadBoundLearningPlan(sessionId);
            if (draft == null) {
                log.info("当前专注会话没有绑定 ACTIVE 学习计划，sessionId={}", sessionId);
                return ToolExecutionResult.success(JSON.writeValueAsString(Map.of(
                        "bound", false,
                        "message", "当前专注会话没有绑定 ACTIVE 学习计划"
                )));
            }

            // 进度服务按同一个 draftRef 读取最新阶段状态，避免只返回过期的正文快照。
            LearningPlanProgressVO progress = progressService.loadCurrentUser(draft.getDraftRef());
            Map<String, Object> result = buildResult(draft, progress);
            log.info("当前会话学习计划读取完成，sessionId={}，draftRef={}，planVersion={}，semanticVersion={}，stageCount={}",
                    sessionId, draft.getDraftRef(), draft.getVersion(), draft.getSemanticVersion(),
                    progress.getSteps().size());
            return ToolExecutionResult.success(JSON.writeValueAsString(result));
        } catch (SecurityException exception) {
            log.warn("当前会话学习计划读取被拒绝，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("LEARNING_PLAN_ACCESS_DENIED",
                    "当前工具只允许在有效的专注会话中使用", false);
        } catch (ClientDataErrorException exception) {
            log.warn("当前会话学习计划读取失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("LEARNING_PLAN_READ_FAILED",
                    "读取当前学习计划失败，请稍后重试", true);
        } catch (JacksonException exception) {
            log.warn("当前会话学习计划序列化失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("LEARNING_PLAN_SERIALIZE_FAILED",
                    "学习计划结果暂时无法返回，请稍后重试", true);
        } catch (RuntimeException exception) {
            log.warn("当前会话学习计划读取出现系统错误，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("LEARNING_PLAN_READ_FAILED",
                    "读取当前学习计划失败，请稍后重试", false);
        }
    }

    // 组装模型需要的计划字段和阶段进度，不暴露用户 ID、数据库时间等内部字段。
    private Map<String, Object> buildResult(LearningPlanDraft draft, LearningPlanProgressVO progress) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bound", true);
        result.put("draftRef", draft.getDraftRef());
        result.put("title", draft.getTitle());
        result.put("objective", draft.getObjective());
        result.put("learnerProfile", draft.getLearnerProfile());
        result.put("weeklyCommitment", draft.getWeeklyCommitment());
        result.put("constraints", draft.getConstraints());
        result.put("status", draft.getStatus());
        result.put("planVersion", draft.getVersion());
        result.put("semanticVersion", draft.getSemanticVersion());
        List<Map<String, Object>> stages = progress.getSteps().stream().map(this::stageView).toList();
        result.put("currentStage", stages.stream()
                .filter(stage -> stage.get("status") != LearningPlanStepProgressStatus.CONFIRMED)
                .findFirst().orElse(null));
        result.put("stages", stages);
        return result;
    }

    // 把一个计划阶段和它的真实状态合成稳定的模型可读对象。
    private Map<String, Object> stageView(LearningPlanStepProgressVO stage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stageRef", stage.getStepRef());
        result.put("position", stage.getPosition());
        result.put("description", stage.getDescription());
        result.put("completionCriteria", stage.getCompletionCriteria());
        result.put("status", stage.getStatus());
        result.put("evidenceType", stage.getEvidenceType());
        result.put("evidenceSummary", stage.getEvidenceSummary());
        result.put("assessmentReason", stage.getAssessmentReason());
        result.put("evaluatedPlanVersion", stage.getEvaluatedPlanVersion());
        result.put("evaluatedSemanticVersion", stage.getEvaluatedSemanticVersion());
        result.put("progressVersion", stage.getProgressVersion());
        return result;
    }
}
