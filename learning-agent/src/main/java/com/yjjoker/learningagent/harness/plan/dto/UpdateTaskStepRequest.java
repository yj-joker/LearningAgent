package com.yjjoker.learningagent.harness.plan.dto;

import com.yjjoker.learningagent.harness.plan.model.AgentTaskStepStatus;
import lombok.Data;

// 描述一个步骤修改后的完整内容；这是完整快照，不是“只填变化字段”的补丁。
@Data
public class UpdateTaskStepRequest {
    // 旧步骤必须携带原编号；只有新增步骤填 null，编号由后端生成。
    private String stepId;
    private String description;
    private String completionCriteria;
    private AgentTaskStepStatus status;
    // 完成、受阻、取消时必须说明结果或原因；不能填写完整工具正文。
    private String resultSummary;
}
