package com.yjjoker.learningagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

// 返回知识点变化类型和名称，不在变化清单里重复发送整段正文。
@Data
@AllArgsConstructor
public class CourseContentChangeVO {
    // ADDED、REMOVED、REORDERED、CONTENT_CHANGED；同一知识点可有多种变化。
    private String type;
    private Long knowledgePointId;
    private String knowledgePointName;
}
