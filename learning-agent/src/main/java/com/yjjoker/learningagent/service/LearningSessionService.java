package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.LearningSessionDTO;
import com.yjjoker.learningagent.dto.StandaloneLearningSessionDTO;
import com.yjjoker.learningagent.entity.LearningSessionMessage;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.vo.LearningSessionVO;

import java.util.List;

public interface LearningSessionService {
    // 校验课程权限后创建课程学习会话。
    LearningSessionVO createSession(LearningSessionDTO learningSessionDTO);
    // 独立问答或专注会话不绑定课程，每次创建得到新的历史范围。
    LearningSessionVO createStandaloneSession(StandaloneLearningSessionDTO request);
    // 只查询当前登录用户在数据库中保存且未删除的所有会话。
    List<LearningSessionVO> findSessions();
    // 当前用户可把自己的进行中会话标记为已完成。
    LearningSessionVO changeSessionStatus(Long sessionId);

    // 返回给用户展示的消息，不包含工具调用和工具结果。
    List<LearningSessionMessage> findSessionMessages(Long sessionId);

    // 有模式参数时只展示该模式历史；空参数保留旧接口的全部历史行为。
    List<LearningSessionMessage> findSessionMessages(Long sessionId, AgentMode mode);
}
