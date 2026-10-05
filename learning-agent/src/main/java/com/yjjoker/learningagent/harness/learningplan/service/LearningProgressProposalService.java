package com.yjjoker.learningagent.harness.learningplan.service;

import com.yjjoker.learningagent.exception.ClientDataErrorException;
import com.yjjoker.learningagent.harness.learningplan.dto.LearningProgressProposal;
import com.yjjoker.learningagent.harness.plan.service.SessionGoalService;
import com.yjjoker.learningagent.harness.plan.model.LearningPlanTaskScope;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.projectenum.LearningEvidenceType;
import com.yjjoker.learningagent.projectenum.LearningPlanStepProgressStatus;
import com.yjjoker.learningagent.service.LearningPlanProgressService;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.LearningPlanProgressVO;
import com.yjjoker.learningagent.vo.LearningPlanStepProgressVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 负责解析、校验和执行长期学习进度申请；只有批准后的 execute 才会修改数据库。
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningProgressProposalService {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> FIELDS = Set.of(
            "draftRef", "stepRef", "planVersion", "evaluatedPlanVersion", "evaluatedSemanticVersion",
            "expectedProgressVersion", "targetStatus", "evidenceType", "evidenceSummary",
            "assessmentReason", "userEvidence");

    private final LearningPlanProgressService progressService;
    private final SessionGoalService sessionGoalService;

    // 返回工具参数结构；模型提交的是证据提案，不是直接数据库更新命令。
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("draftRef", text("当前 ACTIVE 学习计划的 draftRef", 64));
        properties.put("stepRef", text("当前计划步骤的 stepRef", 64));
        properties.put("planVersion", integer("当前计划版本"));
        properties.put("evaluatedPlanVersion", integer("生成证据判断时看到的计划版本，必须与 planVersion 相同"));
        properties.put("evaluatedSemanticVersion", integer("生成证据判断时看到的计划语义版本"));
        properties.put("expectedProgressVersion", integer("当前步骤进度版本"));
        properties.put("targetStatus", Map.of("type", "string", "enum", List.of("IN_PROGRESS", "CONFIRMED"),
                "description", "申请开始学习或建议确认掌握"));
        properties.put("evidenceType", Map.of("type", "string", "enum", List.of("EXPLANATION", "EXERCISE", "BOTH"),
                "description", "用户自己的解释、独立练习，或两者都有"));
        properties.put("evidenceSummary", text("证据摘要，不要复制整段答案", 2000));
        properties.put("assessmentReason", text("模型判断理由，供用户审批核对", 2000));
        properties.put("userEvidence", text("逐字引用本轮用户消息中的连续原文", 800));
        return Map.of("type", "object", "properties", properties,
                "required", List.copyOf(FIELDS), "additionalProperties", false);
    }

    // 解析 JSON 并拒绝重复字段、尾随内容和未声明字段。
    public LearningProgressProposal parse(String input) throws JacksonException {
        if (input == null || input.length() > 12_000) throw new IllegalArgumentException("进度申请过大");
        JsonNode root = JSON.readTree(input);
        if (root == null || !root.isObject() || !names(root).equals(FIELDS)) {
            throw new IllegalArgumentException("进度申请字段不完整或包含未允许字段");
        }
        LearningProgressProposal proposal = new LearningProgressProposal();
        proposal.setDraftRef(requiredText(root, "draftRef", 64));
        proposal.setStepRef(requiredText(root, "stepRef", 64));
        proposal.setPlanVersion(requiredPositive(root, "planVersion"));
        proposal.setEvaluatedPlanVersion(requiredPositive(root, "evaluatedPlanVersion"));
        proposal.setEvaluatedSemanticVersion(requiredPositive(root, "evaluatedSemanticVersion"));
        proposal.setExpectedProgressVersion(requiredPositive(root, "expectedProgressVersion"));
        proposal.setTargetStatus(enumValue(root, "targetStatus", LearningPlanStepProgressStatus.class));
        proposal.setEvidenceType(enumValue(root, "evidenceType", LearningEvidenceType.class));
        proposal.setEvidenceSummary(requiredText(root, "evidenceSummary", 2000));
        proposal.setAssessmentReason(requiredText(root, "assessmentReason", 2000));
        proposal.setUserEvidence(requiredText(root, "userEvidence", 800));
        return proposal;
    }

    // 预检和正式执行共用所有规则；预检只读数据库，execute=false 不产生写入。
    public ToolExecutionResult validate(LearningProgressProposal proposal, String currentUserMessage,
                                        Long sessionId, String boundDraftRef, LearningPlanProgressVO snapshot,
                                        boolean execute) {
        return validate(proposal, currentUserMessage, sessionId, boundDraftRef, snapshot,
                null, null, execute);
    }

    // 校验长期进度只能修改当前短期任务绑定的阶段，计划外任务不能越权修改长期计划。
    public ToolExecutionResult validate(LearningProgressProposal proposal, String currentUserMessage,
                                        Long sessionId, String boundDraftRef, LearningPlanProgressVO snapshot,
                                        String boundStageRef, LearningPlanTaskScope boundScope,
                                        boolean execute) {
        try {
            validateRequest(proposal, currentUserMessage, boundDraftRef, snapshot, boundStageRef, boundScope);
            Long userId = requireUser();
            validateSessionBinding(sessionId, proposal.getDraftRef());
            progressService.validateProposal(userId, proposal);
            if (!execute) {
                log.info("长期学习进度申请预检通过，userId={}，draftRef={}，stepRef={}，targetStatus={}，evidenceType={}，execute=false",
                        userId, proposal.getDraftRef(), proposal.getStepRef(), proposal.getTargetStatus(), proposal.getEvidenceType());
                return ToolExecutionResult.success("掌握进度申请已校验，等待用户审批；当前尚未更新长期计划");
            }
            LearningPlanProgressVO updated = progressService.applyApprovedProposal(userId, proposal);
            log.info("长期学习进度已更新，userId={}，draftRef={}，stepRef={}，targetStatus={}，evidenceType={}，planVersion={}",
                    userId, proposal.getDraftRef(), proposal.getStepRef(), proposal.getTargetStatus(),
                    proposal.getEvidenceType(), updated.getPlanVersion());
            return ToolExecutionResult.success(JSON.writeValueAsString(Map.of(
                    "updated", true, "draftRef", updated.getDraftRef(), "stepRef", proposal.getStepRef(),
                    "status", updated.getSteps().stream().filter(step -> step.getStepRef().equals(proposal.getStepRef()))
                            .findFirst().map(LearningPlanStepProgressVO::getStatus).orElse(null),
                    "planVersion", updated.getPlanVersion(), "semanticVersion", updated.getSemanticVersion())));
        } catch (ClientDataErrorException | IllegalArgumentException | JacksonException exception) {
            log.warn("长期学习进度申请未通过，targetStatus={}，errorType={}",
                    proposal == null ? null : proposal.getTargetStatus(), exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("INVALID_LEARNING_PROGRESS", exception.getMessage(), true);
        } catch (RuntimeException exception) {
            log.error("长期学习进度申请执行失败，errorType={}", exception.getClass().getSimpleName(), exception);
            return ToolExecutionResult.failure("LEARNING_PROGRESS_WRITE_FAILED", "保存长期学习进度失败，请稍后重试", false);
        }
    }

    // 每次预检和批准恢复都读取当前会话绑定，不能只相信暂停时保存的旧快照。
    private void validateSessionBinding(Long sessionId, String draftRef) {
        if (sessionId == null || sessionId <= 0) throw new ClientDataErrorException("缺少有效学习会话");
        var bound = sessionGoalService.loadBoundLearningPlan(sessionId);
        if (bound == null || !draftRef.equals(bound.getDraftRef())) {
            throw new ClientDataErrorException("当前会话关联的学习计划已经变化，请重新读取计划进度");
        }
    }

    // 审批说明只展示目标、证据类型和理由，不把完整用户答案复制到审批标题。
    public String approvalReason(LearningProgressProposal proposal, LearningPlanProgressVO snapshot) {
        LearningPlanStepProgressVO step = snapshot.getSteps().stream()
                .filter(item -> item.getStepRef().equals(proposal.getStepRef())).findFirst()
                .orElseThrow(() -> new ClientDataErrorException("步骤引用已失效，请重新读取学习计划进度"));
        return "申请将步骤“" + step.getDescription() + "”从 " + step.getStatus() + " 更新为 "
                + proposal.getTargetStatus() + "；证据类型：" + proposal.getEvidenceType()
                + "；理由：" + proposal.getAssessmentReason();
    }

    // 执行成功后重新读取最新进度，供工具结果和本轮后续模型请求使用。
    public LearningPlanProgressVO current(String draftRef) {
        return progressService.loadCurrentUser(draftRef);
    }

    // 预检时验证归属、版本、步骤引用和本轮用户证据，防止模型伪造完成依据。
    private void validateRequest(LearningProgressProposal proposal, String currentUserMessage,
                                 String boundDraftRef, LearningPlanProgressVO snapshot,
                                 String boundStageRef, LearningPlanTaskScope boundScope) {
        if (proposal == null || snapshot == null) throw new ClientDataErrorException("当前没有可更新的长期学习计划");
        if (!proposal.getDraftRef().equals(boundDraftRef) || !proposal.getDraftRef().equals(snapshot.getDraftRef())) {
            throw new ClientDataErrorException("申请的学习计划不是当前会话绑定的计划");
        }
        if (proposal.getPlanVersion() != snapshot.getPlanVersion()
                || proposal.getEvaluatedPlanVersion() != snapshot.getPlanVersion()
                || proposal.getEvaluatedSemanticVersion() != snapshot.getSemanticVersion()) {
            throw new ClientDataErrorException("计划版本已变化，请重新读取当前学习计划进度");
        }
        LearningPlanStepProgressVO step = snapshot.getSteps().stream()
                .filter(item -> item.getStepRef().equals(proposal.getStepRef())).findFirst()
                .orElseThrow(() -> new ClientDataErrorException("步骤引用已失效，请重新读取学习计划进度"));
        if (proposal.getExpectedProgressVersion() != step.getProgressVersion()) {
            throw new ClientDataErrorException("步骤进度版本已变化，请重新读取当前进度");
        }
        // 长期进度修改必须指向本次短期任务的快照阶段，不能只凭模型提交的 stepRef。
        if (boundScope != null) {
            if (boundScope == LearningPlanTaskScope.OUT_OF_PLAN || boundStageRef == null
                    || !boundStageRef.equals(proposal.getStepRef())) {
                throw new ClientDataErrorException("当前短期任务不允许修改该长期计划阶段");
            }
        }
        if (proposal.getTargetStatus() == null || proposal.getTargetStatus() == LearningPlanStepProgressStatus.NOT_STARTED) {
            throw new ClientDataErrorException("长期进度只能申请 IN_PROGRESS 或 CONFIRMED");
        }
        if (proposal.getEvidenceType() == null || proposal.getEvidenceSummary() == null
                || proposal.getAssessmentReason() == null || proposal.getUserEvidence() == null
                || currentUserMessage == null || !currentUserMessage.contains(proposal.getUserEvidence())) {
            throw new ClientDataErrorException("申请必须包含本轮用户消息中的真实证据");
        }
        if (proposal.getTargetStatus() == LearningPlanStepProgressStatus.CONFIRMED
                && proposal.getEvidenceType() != LearningEvidenceType.BOTH) {
            throw new ClientDataErrorException("CONFIRMED 必须同时具备解释和独立练习证据");
        }
        if (proposal.getTargetStatus() == LearningPlanStepProgressStatus.CONFIRMED
                && step.getStatus() == LearningPlanStepProgressStatus.CONFIRMED) {
            throw new ClientDataErrorException("该步骤已经确认，不能重复申请确认");
        }
    }

    private Long requireUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) throw new ClientDataErrorException("请先登录");
        return userId;
    }

    private Set<String> names(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (String name : node.propertyNames()) names.add(name);
        return names;
    }

    private String requiredText(JsonNode root, String name, int max) {
        JsonNode node = root.get(name);
        if (node == null || !node.isTextual() || node.asString().isBlank()
                || node.asString().strip().codePointCount(0, node.asString().strip().length()) > max) {
            throw new IllegalArgumentException(name + " 必须是非空字符串且不超过 " + max + " 个字符");
        }
        return node.asString().strip();
    }

    private long requiredPositive(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isIntegralNumber() || node.asLong() < 1) {
            throw new IllegalArgumentException(name + " 必须是正整数");
        }
        return node.asLong();
    }

    private <E extends Enum<E>> E enumValue(JsonNode root, String name, Class<E> type) {
        String value = requiredText(root, name, 32);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " 值不合法");
        }
    }

    private Map<String, Object> text(String description, int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max, "description", description);
    }

    private Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "minimum", 1, "description", description);
    }
}
