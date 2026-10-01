package com.yjjoker.learningagent.harness.plan.service;

import com.yjjoker.learningagent.harness.plan.model.SessionGoalSnapshot;
import com.yjjoker.learningagent.utils.BaseContext;
import org.springframework.stereotype.Component;
import java.util.Objects;

// 只在专注模式绑定；工具从后端取用户和会话，不接受模型指定操作别人的会话。
@Component
public class SessionGoalContext {
    private final ThreadLocal<SessionGoalSnapshot> current = new ThreadLocal<>();

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
    }
}
