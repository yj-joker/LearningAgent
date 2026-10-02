package com.yjjoker.learningagent.harness.review;

import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.plan.dto.SessionGoalProgress;
import lombok.Getter;
import java.util.List;

// 审查使用的本轮快照；草稿还没有落库，progress 来自后端而不是模型回答。
@Getter
public class AnswerReviewRequest {
    private final String userMessage;
    private final String draftAnswer;
    private final List<LlmMessage> dialogue;
    private final SessionGoalProgress progress;

    // 固定消息列表；审查服务只挑选必要对话，不把系统提示词和全部工具正文重复发送。
    public AnswerReviewRequest(String userMessage, String draftAnswer,
                               List<LlmMessage> dialogue, SessionGoalProgress progress) {
        this.userMessage = userMessage;
        this.draftAnswer = draftAnswer;
        this.dialogue = List.copyOf(dialogue);
        this.progress = progress;
    }
}
