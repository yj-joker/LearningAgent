package com.yjjoker.learningagent.harness.plan.model;

import lombok.Getter;

// 保存意图识别模型对本轮用户消息的判断，不保存数据库状态，也不授予工具权限。
@Getter
public class GoalIntent {
    private boolean progressReadOnly;
    private boolean progressMutation;
    private boolean planMutation;
    private double confidence;
    private String reason;

    // Jackson 从审批检查点恢复对象时使用无参构造器，再填充字段。
    public GoalIntent() {
        this(false, false, false, 0.0, "");
    }

    // 用固定字段创建不可变快照，审批恢复时可以原样复用。
    public GoalIntent(boolean progressReadOnly,
                      boolean progressMutation,
                      boolean planMutation,
                      double confidence,
                      String reason) {
        this.progressReadOnly = progressReadOnly;
        this.progressMutation = progressMutation;
        this.planMutation = planMutation;
        this.confidence = Math.clamp(confidence, 0.0, 1.0);
        this.reason = reason == null ? "" : reason;
    }

    // 下面的 setter 只供检查点反序列化使用，运行中仍把对象当作固定快照读取。
    public void setProgressReadOnly(boolean progressReadOnly) {
        this.progressReadOnly = progressReadOnly;
    }

    public void setProgressMutation(boolean progressMutation) {
        this.progressMutation = progressMutation;
    }

    public void setPlanMutation(boolean planMutation) {
        this.planMutation = planMutation;
    }

    public void setConfidence(double confidence) {
        this.confidence = Math.clamp(confidence, 0.0, 1.0);
    }

    public void setReason(String reason) {
        this.reason = reason == null ? "" : reason;
    }

    // 意图识别失败时使用保守快照，不把普通问题误判成状态变更。
    public static GoalIntent unknown() {
        return new GoalIntent(false, false, false, 0.0, "识别失败或意图不明确");
    }
}
