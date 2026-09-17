package com.yjjoker.learningagent.entity;

import lombok.Data;

@Data
//文档切分元数据，用于标识文档的分页和切块索引
public class DocumentChunksMetadata {
    private Long documentId;
    private Integer page;
    private Integer chunkIndex;
}
