package com.yjjoker.learningagent.harness.plan.model;

import lombok.Data;

// 会话的当前目标指针；只有一行，因此不会同时出现两个当前目标。
@Data
public class SessionFocusState {
    private Long sessionId;
    private Long userId;
    private String activePlanId;
    // 切换回同一个目标也有新版本，能发现 A -> B -> A 期间的变化。
    private long version;
    private int nextGoalNumber;
}
