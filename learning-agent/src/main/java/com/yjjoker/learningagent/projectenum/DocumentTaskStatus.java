package com.yjjoker.learningagent.projectenum;

// 文档处理任务的生命周期状态，对应 document_tasks.status 中保存的字符串。
public enum DocumentTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}
