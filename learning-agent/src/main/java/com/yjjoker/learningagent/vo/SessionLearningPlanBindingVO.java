package com.yjjoker.learningagent.vo;

import lombok.Getter;

// 对外返回会话的长期计划引用和关联版本，不暴露短期目标指针。
@Getter
public class SessionLearningPlanBindingVO {
    private final Long sessionId;
    private final String draftRef;
    private final long bindingVersion;
    private final String title;

    // 未关联时 title 和 draftRef 都为空，bindingVersion 仍用于下一次并发校验。
    public SessionLearningPlanBindingVO(Long sessionId, String draftRef, long bindingVersion, String title) {
        this.sessionId = sessionId;
        this.draftRef = draftRef;
        this.bindingVersion = bindingVersion;
        this.title = title;
    }
}
