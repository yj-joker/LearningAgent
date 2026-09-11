package com.yjjoker.learningagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentChunks {
    private Long id;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private String vectorId;
    private String metadata;
    private LocalDateTime createdAt;
}
