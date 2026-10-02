package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.harness.plan.model.SessionGoalSnapshot;
import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.utils.BaseContext;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.List;

// 只在专注模式绑定；工具从后端取用户和会话，不接受模型指定操作别人的会话。
@Component
public class SessionGoalContext {
    private final ThreadLocal<SessionGoalSnapshot> current = new ThreadLocal<>();
    // 只保留本轮可见用户原话，用于核对进度申请中的引用；不复制完整聊天到步骤表。
    private final ThreadLocal<List<String>> dialogue = new ThreadLocal<>();
    // 保存本次用户原始问题，用于阻止明确的只读进度查询误触发状态写入。
    private final ThreadLocal<String> userMessage = new ThreadLocal<>();

    // 绑定本轮用户消息；恢复审批时绑定检查点中的原始问题。
    public void bindUserMessage(String message) {
        userMessage.set(message == null ? "" : message);
    }

    // 只识别明确的查询语句；包含开始、完成等变更意图时不拦截。
    public boolean isExplicitProgressReadOnlyRequest() {
        String message = userMessage.get();
        if (message == null || message.isBlank()) return false;
        String normalized = message.replaceAll("\\s+", "").toLowerCase();
        boolean query = containsAny(normalized, "当前进度", "步骤状态", "计划状态", "有几个步骤",
                "列出步骤", "查看步骤", "查看进度", "查询进度", "目前做到哪", "完成了几步");
        boolean mutation = containsAny(normalized, "开始", "完成", "通过", "进入下一步", "更新", "标记",
                "阻塞", "取消", "继续执行");
        return query && !mutation;
    }

    // 只识别明确要求改变步骤状态的表达，不把普通教学问题当成进度变更。
    public boolean isExplicitProgressMutationRequest() {
        // TODO 后续接入独立意图识别器，替代关键词触发并识别更多自然语言变体；后端状态校验仍不可省略。
        String message = userMessage.get();
        if (message == null || message.isBlank() || isExplicitProgressReadOnlyRequest()) return false;
        String normalized = message.replaceAll("\\s+", "").toLowerCase();
        return containsAny(normalized,
                "开始第一步", "开始第", "开始学习", "进入下一步", "继续下一步",
                "完成第一步", "完成第", "标记完成", "标记为完成",
                "本步骤通过", "步骤通过", "更新步骤", "更新进度",
                "阻塞当前步骤", "取消当前步骤", "我已经理解了请继续");
    }

    // 判断短语是否存在；只用于边界保护，不负责理解复杂自然语言。
    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    // 新请求、摘要后和审批恢复时重新绑定，不能拿已移出上下文的原话冒充当前证据。
    public void bindDialogue(List<LlmMessage> messages) {
        require();
        dialogue.set(messages.stream().filter(message -> "user".equals(message.getRole()))
                .map(LlmMessage::getContent).filter(Objects::nonNull).toList());
    }

    // 验证模型引用确实来自用户；存在引用不代表它满足完成条件，仍由用户审批。
    public boolean containsUserEvidence(String evidence) {
        require();
        List<String> messages = dialogue.get();
        return evidence != null && !evidence.isBlank() && messages != null
                && messages.stream().anyMatch(message -> message.contains(evidence));
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
        userMessage.remove();
    }
}
