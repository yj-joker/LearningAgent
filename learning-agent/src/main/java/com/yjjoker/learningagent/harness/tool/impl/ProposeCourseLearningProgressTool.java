package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.course.CourseLearningRunContext;
import com.yjjoker.learningagent.harness.course.model.CourseProgressProposal;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.projectenum.CourseLearningPointStatus;
import com.yjjoker.learningagent.service.CourseLearningProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 课程进度工具只提交掌握建议，正式状态必须经过通用用户审批。
@Component
@RequiredArgsConstructor
@Slf4j
public class ProposeCourseLearningProgressTool implements Tool {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> FIELDS = Set.of(
            "pointRef", "expectedVersion", "targetStatus", "evidenceType",
            "evidenceSummary", "assessmentReason", "userEvidence");

    private final CourseLearningProgressService service;
    private final CourseLearningRunContext context;

    // 注册表使用固定名称把模型调用映射到这个 Java 工具。
    @Override
    public String name() { return "propose_course_learning_progress"; }

    // 说明课程进度只能通过证据提议和用户审批改变。
    @Override
    public String description() {
        return "为当前课程知识点提交学习进度建议。targetStatus 只能是 IN_PROGRESS 或 CONFIRMED；"
                + "NOT_STARTED 或 REVIEW_REQUIRED 可以申请 IN_PROGRESS；CONFIRMED 必须同时有解释和独立练习证据，"
                + "旧正文证据不能证明修改后的正文已掌握。需要用户审批，不能把提议说成已经完成。";
    }

    // 课程状态是用户数据，任何写入都需要用户确认。
    @Override
    public boolean requiresUserApproval() { return true; }

    // 一个审批批次只处理一项课程进度，避免同轮跳过知识点。
    @Override
    public boolean requiresExclusiveBatch() { return true; }

    // 参数绑定当前课程快照，不能在未来请求重放旧引用。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 返回严格字段结构，减少模型输出多余字段导致的审批歧义。
    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pointRef", text("从当前课程索引复制的知识点引用", 80));
        properties.put("expectedVersion", Map.of("type", "integer", "minimum", 1));
        properties.put("targetStatus", Map.of("type", "string", "enum", List.of("IN_PROGRESS", "CONFIRMED")));
        properties.put("evidenceType", Map.of("type", "string", "enum", List.of("EXPLANATION", "EXERCISE", "BOTH")));
        properties.put("evidenceSummary", text("学习证据摘要", 2000));
        properties.put("assessmentReason", text("判断理由，供用户审批核对", 2000));
        properties.put("userEvidence", text("本轮用户消息中的连续原文", 800));
        return Map.of("type", "object", "properties", properties,
                "required", List.copyOf(FIELDS), "additionalProperties", false);
    }

    // 审批前只读取和校验，不写课程进度。
    @Override
    public ToolExecutionResult validateApprovalInput(String input) {
        try {
            CourseProgressProposal proposal = parse(input);
            return service.validateProposal(context.require().getSessionId(), context.require(), proposal,
                    context.currentUserMessage());
        } catch (RuntimeException exception) {
            return ToolExecutionResult.failure("INVALID_COURSE_PROGRESS",
                    exception.getMessage() == null ? "课程进度申请参数不合法" : exception.getMessage(), true);
        }
    }

    // 审批卡显示目标知识点、状态变化和模型理由，不显示完整答案正文。
    @Override
    public String approvalReason(String input) {
        CourseProgressProposal proposal = parse(input);
        var snapshot = context.require();
        var pointId = context.resolvePointRef(proposal.getPointRef());
        var point = snapshot.getPoints().stream()
                .filter(item -> item.getKnowledgePointId().equals(pointId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("知识点引用已失效"));
        return "课程知识点“" + point.getKnowledgePointName() + "”申请从 " + point.getStatus()
                + " 更新为 " + proposal.getTargetStatus() + "；理由：" + proposal.getAssessmentReason();
    }

    // 用户批准后重新锁定并写入，写入成功才刷新本轮课程快照。
    @Override
    public ToolExecutionResult execute(String input) {
        try {
            CourseProgressProposal proposal = parse(input);
            ToolExecutionResult result = service.applyApprovedProposal(context.require().getSessionId(),
                    context.require(), proposal, context.currentUserMessage());
            if (result.isSuccess()) {
                context.bind(service.load(context.require().getSessionId()), context.currentUserMessage());
            }
            return result;
        } catch (RuntimeException exception) {
            log.warn("课程进度批准写入失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("COURSE_PROGRESS_WRITE_FAILED",
                    exception.getMessage() == null ? "保存课程进度失败" : exception.getMessage(), false);
        }
    }

    // 严格解析 JSON，拒绝重复字段、尾随内容和未声明字段。
    private CourseProgressProposal parse(String input) throws JacksonException {
        if (input == null || input.length() > 12_000) throw new IllegalArgumentException("课程进度申请过大");
        JsonNode root = JSON.readTree(input);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("课程进度申请必须是 JSON 对象");
        Set<String> names = new java.util.HashSet<>();
        for (String name : root.propertyNames()) names.add(name);
        if (!names.equals(FIELDS)) throw new IllegalArgumentException("课程进度申请字段不完整或包含未允许字段");
        CourseProgressProposal proposal = new CourseProgressProposal();
        proposal.setPointRef(requiredText(root, "pointRef", 80));
        proposal.setExpectedVersion(requiredPositive(root, "expectedVersion"));
        proposal.setTargetStatus(enumValue(root, "targetStatus", CourseLearningPointStatus.class));
        proposal.setEvidenceType(enumValue(root, "evidenceType", com.yjjoker.learningagent.projectenum.LearningEvidenceType.class));
        proposal.setEvidenceSummary(requiredText(root, "evidenceSummary", 2000));
        proposal.setAssessmentReason(requiredText(root, "assessmentReason", 2000));
        proposal.setUserEvidence(requiredText(root, "userEvidence", 800));
        return proposal;
    }

    // 读取非空文本并限制长度，防止超出数据库字段范围。
    private String requiredText(JsonNode root, String name, int max) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.asString().isBlank()
                || value.asString().strip().codePointCount(0, value.asString().strip().length()) > max) {
            throw new IllegalArgumentException(name + " 必须是非空字符串且不超过 " + max + " 个字符");
        }
        return value.asString().strip();
    }

    // 版本必须是正整数，不能把模型的小数或字符串静默转换。
    private long requiredPositive(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || value.asLong() < 1) {
            throw new IllegalArgumentException(name + " 必须是正整数");
        }
        return value.asLong();
    }

    // 只接受后端定义的状态和证据类型。
    private <E extends Enum<E>> E enumValue(JsonNode root, String name, Class<E> type) {
        String value = requiredText(root, name, 32);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " 值不合法");
        }
    }

    // 为文本参数生成统一的模型输入限制。
    private Map<String, Object> text(String description, int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max, "description", description);
    }
}
