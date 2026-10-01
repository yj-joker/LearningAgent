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
    }
}
