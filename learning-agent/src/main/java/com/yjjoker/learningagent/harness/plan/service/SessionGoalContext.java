package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.harness.plan.model.SessionGoalSnapshot;
import com.yjjoker.learningagent.harness.plan.model.GoalIntent;
import com.yjjoker.learningagent.entity.LearningPlanDraft;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.LearningPlanProgressVO;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.List;

// 只在专注模式绑定；工具从后端取用户和会话，不接受模型指定操作别人的会话。
@Component
public class SessionGoalContext {
    private final ThreadLocal<SessionGoalSnapshot> current = new ThreadLocal<>();
    // 只保留本轮可见用户原话，用于核对进度申请中的引用；不复制完整聊天到步骤表。
    private final ThreadLocal<List<String>> dialogue = new ThreadLocal<>();
    // 保存独立意图模型生成的本轮快照，Hook 和工具共用同一份判断。
    private final ThreadLocal<GoalIntent> goalIntent = new ThreadLocal<>();
    // 保存本次 AgentLoop 开始时读取的长期计划快照，循环中途不热替换。
    private final ThreadLocal<LearningPlanDraft> learningPlan = new ThreadLocal<>();
    // 保存本次 AgentLoop 开始时读取的长期计划进度，教学过程中不动态读取数据库。
    private final ThreadLocal<LearningPlanProgressVO> learningPlanProgress = new ThreadLocal<>();

    // 绑定本轮意图快照；调用方必须先完成后端识别再进入 Agent Loop。
    public void bindIntent(GoalIntent intent) {
        goalIntent.set(intent == null ? GoalIntent.unknown() : intent);
    }

    // 绑定本轮长期计划快照；传空表示会话没有关联学习计划。
    public void bindLearningPlan(LearningPlanDraft draft) {
        learningPlan.set(draft);
    }

    // 返回本轮固定的长期计划快照。
    public LearningPlanDraft getLearningPlan() {
        return learningPlan.get();
    }

    // 绑定本轮长期计划步骤进度，模型只能读取这份快照。
    public void bindLearningPlanProgress(LearningPlanProgressVO progress) {
        learningPlanProgress.set(progress);
    }

    // 返回本轮固定的长期计划步骤进度。
    public LearningPlanProgressVO getLearningPlanProgress() {
        return learningPlanProgress.get();
    }

    // 返回当前意图快照，审批检查点会保存并在恢复时重新绑定。
    public GoalIntent getGoalIntent() {
        GoalIntent intent = goalIntent.get();
        return intent == null ? GoalIntent.unknown() : intent;
    }

    // 判断用户是否只查询进度；结果来自独立意图模型，不再扫描关键词。
    public boolean isExplicitProgressReadOnlyRequest() {
        return getGoalIntent().isProgressReadOnly();
    }

    // 判断用户是否明确要求改变步骤状态；结果来自独立意图模型。
    public boolean isExplicitProgressMutationRequest() {
        return getGoalIntent().isProgressMutation();
    }

    // 判断用户是否明确要求改变计划结构；结果来自独立意图模型。
    public boolean isExplicitPlanMutationRequest() {
        return getGoalIntent().isPlanMutation();
    }

    // 新请求、摘要后和审批恢复时只绑定当前 AgentLoop 的最后一条用户消息。
    public void bindDialogue(List<LlmMessage> messages) {
        require();
        String current = messages.stream().filter(message -> "user".equals(message.getRole()))
                .map(LlmMessage::getContent).filter(Objects::nonNull).reduce((first, last) -> last).orElse(null);
        dialogue.set(current == null ? List.of() : List.of(current));
    }

    // 验证模型引用来自本轮当前用户消息；存在引用不代表它满足完成条件，仍由用户审批。
    public boolean containsUserEvidence(String evidence) {
        require();
        List<String> messages = dialogue.get();
        return evidence != null && !evidence.isBlank() && messages != null
                && messages.stream().anyMatch(message -> message.contains(evidence));
    }

    // 返回本轮用户最后一条原话，长期进度工具据此核对证据引用。
    public String currentUserMessage() {
        List<String> messages = dialogue.get();
        return messages == null || messages.isEmpty() ? null : messages.getLast();
    }

    // 初次加载、批准后更新和检查点恢复都绑定明确的快照。
    public void bind(SessionGoalSnapshot snapshot) {
        if (snapshot == null || snapshot.getState() == null || snapshot.getCurrentPlan() == null
                || !Objects.equals(snapshot.getState().getUserId(), BaseContext.getCurrentId())
                || !Objects.equals(snapshot.getState().getActivePlanId(), snapshot.getCurrentPlan().getPlanId())) {
            throw new SecurityException("目标上下文不完整或归属不一致");
        }
        current.set(snapshot);
    }

    // CHAT 没有绑定目标；即使模型猜中工具名，也不能执行专注目标操作。
    public SessionGoalSnapshot require() {
        SessionGoalSnapshot snapshot = current.get();
        if (snapshot == null || BaseContext.getCurrentId() == null
                || !Objects.equals(snapshot.getState().getUserId(), BaseContext.getCurrentId())) {
            throw new SecurityException("目标工具仅供当前用户的专注模式使用");
        }
        return snapshot;
    }

    // 无论成功、审批暂停还是异常，都由 Harness 在 finally 中清理。
    public void clear() {
        current.remove();
        dialogue.remove();
        goalIntent.remove();
        learningPlan.remove();
        learningPlanProgress.remove();
    }
}
