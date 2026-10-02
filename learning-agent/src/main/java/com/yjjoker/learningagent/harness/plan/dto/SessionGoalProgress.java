package com.yjjoker.learningagent.harness.plan.dto;

import com.yjjoker.learningagent.harness.plan.model.AgentTaskPlan;
import com.yjjoker.learningagent.harness.plan.model.AgentTaskStep;
import com.yjjoker.learningagent.harness.plan.model.SessionGoalSnapshot;
import lombok.Data;

import java.util.List;

// 统一的当前目标进度视图，供提示词、查询工具和 HTTP 响应复用同一份数据库快照。
@Data
public class SessionGoalProgress {
    private String goalRef;
    private Integer goalNumber;
    private String goal;
    private String constraints;
    private long planVersion;
    private List<SessionGoalStepProgress> steps = List.of();

    // 只从当前目标快照生成展示数据，不读取模型文字，也不改变数据库状态。
    public static SessionGoalProgress from(SessionGoalSnapshot snapshot) {
        AgentTaskPlan plan = snapshot.getCurrentPlan();
        SessionGoalProgress progress = new SessionGoalProgress();
        progress.setGoalRef("goal-" + plan.getGoalNumber());
        progress.setGoalNumber(plan.getGoalNumber());
        progress.setGoal(plan.getGoal());
        progress.setConstraints(plan.getConstraints());
        progress.setPlanVersion(plan.getVersion());
        progress.setSteps(plan.getSteps().stream()
                .map(step -> SessionGoalStepProgress.from(stepRef(plan, step), step))
                .toList());
        return progress;
    }

    // stepRef 包含计划版本，旧版本引用不能误命中新计划中的同一位置。
    private static String stepRef(AgentTaskPlan plan, AgentTaskStep step) {
        return "goal-" + plan.getGoalNumber() + "-v" + plan.getVersion() + "-step-" + step.getPosition();
    }
}
