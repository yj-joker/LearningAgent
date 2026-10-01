package com.yjjoker.learningagent.harness.plan.model;

import lombok.Data;
import java.util.List;

// 本次请求读取的目标快照；等待审批时一起保存，恢复时不能悄悄换成新版本。
@Data
public class SessionGoalSnapshot {
    private SessionFocusState state;
    // 当前目标带完整步骤，其他目标只带索引，不把全部步骤都塞进模型上下文。
    private AgentTaskPlan currentPlan;
    private List<AgentTaskPlan> goals = List.of();

    // 短引用由数据库中固定序号生成，不随每次 AgentLoop 重新编号。
    public AgentTaskPlan resolve(String goalRef) {
        return goals.stream().filter(goal -> ("goal-" + goal.getGoalNumber()).equals(goalRef))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("目标引用不存在，请调用 list_session_goals"));
    }
}
