package com.yjjoker.learningagent.harness.review;

import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalProgress;
import com.yjjoker.learningagent.vo.LearningPlanProgressVO;
import lombok.Getter;
import java.util.List;

// 审查使用的本轮快照；草稿还没有落库，progress 来自后端而不是模型回答。
@Getter
public class AnswerReviewRequest {
    private final String userMessage;
    private final String draftAnswer;
    private final List<LlmMessage> dialogue;
    private final SessionGoalProgress progress;
    // 长期学习计划进度来自数据库，用于判断本轮是否漏掉阶段确认动作。
    private final LearningPlanProgressVO learningPlanProgress;
    // 当前短期任务绑定的长期阶段引用，用于定位应该检查哪一个阶段。
    private final String learningPlanStageRef;

    // 固定消息列表；审查服务只挑选必要对话，不把系统提示词和全部工具正文重复发送。
    public AnswerReviewRequest(String userMessage, String draftAnswer,
                               List<LlmMessage> dialogue, SessionGoalProgress progress) {
        this(userMessage, draftAnswer, dialogue, progress, null, null);
    }

    // 最终回答审查同时接收短期任务进度和长期学习进度，两者不能混为一谈。
    public AnswerReviewRequest(String userMessage, String draftAnswer,
                               List<LlmMessage> dialogue, SessionGoalProgress progress,
                               LearningPlanProgressVO learningPlanProgress,
                               String learningPlanStageRef) {
        this.userMessage = userMessage;
        this.draftAnswer = draftAnswer;
        this.dialogue = List.copyOf(dialogue);
        this.progress = progress;
        this.learningPlanProgress = learningPlanProgress;
        this.learningPlanStageRef = learningPlanStageRef;
    }
}
