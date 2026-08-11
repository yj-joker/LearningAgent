package com.yjjoker.learningagent.vo;

import lombok.Data;

@Data
public class ChaptersVO {
    private Long id;
    private Long courseId;
    private String title;
    private Long sortOrder;
}
