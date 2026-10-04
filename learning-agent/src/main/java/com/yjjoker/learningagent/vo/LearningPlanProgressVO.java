package com.yjjoker.learningagent.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

// 返回一份长期学习计划的当前进度快照，不把模型回答当成状态来源。
@Getter
public class LearningPlanProgressVO {
    // 计划引用和当前版本用于后续读取与审批校验。
    private final String draftRef;
    private final String title;
    private final long planVersion;
    private final long semanticVersion;
    private final List<LearningPlanStepProgressVO> steps;

    // 固定列表快照，避免调用方修改服务层结果。
    @JsonCreator
    public LearningPlanProgressVO(@JsonProperty("draftRef") String draftRef,
                                  @JsonProperty("title") String title,
                                  @JsonProperty("planVersion") long planVersion,
                                  @JsonProperty("semanticVersion") long semanticVersion,
                                  @JsonProperty("steps") List<LearningPlanStepProgressVO> steps) {
        this.draftRef = draftRef;
        this.title = title;
        this.planVersion = planVersion;
        this.semanticVersion = semanticVersion;
        this.steps = List.copyOf(steps);
    }
}
