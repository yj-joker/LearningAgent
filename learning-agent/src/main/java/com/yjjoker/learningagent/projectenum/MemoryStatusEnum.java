package com.yjjoker.learningagent.projectenum;

// 统一表示长期记忆和会话记忆是否还能被索引和召回。
public enum MemoryStatusEnum {
    // 有效状态会出现在索引和正文召回结果中。
    ACTIVE,
    // 删除状态只保留在数据库中，不再被正常查询返回。
    DELETED
}
