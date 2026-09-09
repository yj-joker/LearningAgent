package com.yjjoker.learningagent.entity;

import com.yjjoker.learningagent.projectenum.DocumentEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Documents {
    private Long id;
    private Long kbId;
    private String filename;
    private String objectName;
    private DocumentEnum status;
    private Long fileSize;
    private String mimeType;
    private Long uploadUserId;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
