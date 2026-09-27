package com.yjjoker.learningagent.harness.memory.model;

import lombok.Data;

// 保存一个用户或会话的整理进度，服务重启后继续使用。
@Data
public class MemoryConsolidationState {
    private MemoryScope scope;
    private Long ownerId;
    // 只累计正常新增、更新和删除；整理自己的合并不再增加计数。
    private long changeCount;
    // 上次成功检查到的累计次数；失败时不推进。
    private long processedCount;
}
