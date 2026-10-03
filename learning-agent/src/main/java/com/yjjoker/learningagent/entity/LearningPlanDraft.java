package com.yjjoker.learningagent.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 保存用户可跨会话继续编辑的学习计划草案；草案不会直接变成正式专注计划。
@Data
public class LearningPlanDraft {
    // draftRef 是模型和接口共同使用的稳定引用，不暴露数据库自增主键。
    private String draftRef;
    // 草案属于哪个用户，始终由后端登录身份写入。
    private Long userId;
    // 草案展示标题。
    private String title;
    // 用户希望最终达到的总体目标。
    private String objective;
    // 用户当前基础，供后续讨论调整难度。
    private String learnerProfile;
    // 用户每周可投入的学习时间。
    private String weeklyCommitment;
    // 用户提出的时间、设备或内容限制。
    private String constraints;
    // 只允许 DRAFT，明确表示内容尚未正式生效。
    private String status = "DRAFT";
    // MANUAL 或 AGENT，用于展示来源，不改变审批规则。
    private String source;
    // 每次成功更新递增，拒绝旧页面覆盖新版本。
    private long version;
    // 草案首次创建时间。
    private LocalDateTime createdAt;
    // 草案最近修改时间。
    private LocalDateTime updatedAt;
    // 草案的完整阶段步骤。
    private List<LearningPlanDraftStep> steps = new ArrayList<>();
}
