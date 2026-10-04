package com.yjjoker.learningagent.projectenum;

// 学习掌握判断使用的证据类型；当前只支持解释和独立练习。
public enum LearningEvidenceType {
    // 用户用自己的话解释知识点。
    EXPLANATION,
    // 用户完成变化后的独立练习。
    EXERCISE,
    // 同时具备解释和独立练习证据。
    BOTH
}
