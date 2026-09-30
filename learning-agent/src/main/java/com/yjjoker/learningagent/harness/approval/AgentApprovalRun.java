package com.yjjoker.learningagent.harness.approval;

import com.yjjoker.learningagent.harness.model.AgentRunStatus;
import lombok.Data;

// 数据库运行记录只供服务层使用，完整上下文不能从查询接口直接泄露出去。
@Data
public class AgentApprovalRun {
    private String runId;
    private Long userId;
    private Long sessionId;
    private int batchNumber;
    private AgentRunStatus status;
    private String checkpointJson;
    private String answer;
}

