package com.yjjoker.learningagent.harness.approval;

import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionTarget;
import com.yjjoker.learningagent.harness.model.AgentMode;
import com.yjjoker.learningagent.harness.plan.model.SessionGoalSnapshot;
import com.yjjoker.learningagent.harness.plan.model.GoalIntent;
import com.yjjoker.learningagent.entity.LearningPlanDraft;
import com.yjjoker.learningagent.vo.LearningPlanProgressVO;
import com.yjjoker.learningagent.harness.skill.model.SkillCheckpointEntry;
import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 检查点是暂停任务的数据快照，不保存线程、连接或 Spring 对象。
@Data
public class AgentRunCheckpoint {
    // 不兼容变更才升级版本；可选新字段提供默认值，兼容已有检查点。
    private int version = 1;
    private String runId;
    private Long userId;
    private Long sessionId;
    // 保存暂停时的模式，恢复后不能被前端的新选择覆盖。
    private AgentMode mode = AgentMode.CHAT;
    // 专注目标与版本随审批保存；旧专注检查点缺失此字段时拒绝继续，不猜测目标。
    private SessionGoalSnapshot goalSnapshot;
    // 保存意图识别快照；审批恢复不重复调用识别模型，也不因上下文变化改变原判断。
    private GoalIntent goalIntent;
    // 保存本次循环开始时的长期计划快照，审批恢复不读取中途新版本。
    private LearningPlanDraft learningPlan;
    // 保存暂停时的长期步骤进度，恢复后继续使用同一份教学上下文。
    private LearningPlanProgressVO learningPlanProgress;
    // 不含动态目标块，恢复后可以整体重建系统消息，避免旧目标与新目标同时生效。
    private String systemPromptBase;
    // 旧检查点缺少此字段时为空；恢复已加载技能前核对版本和内容指纹。
    private List<SkillCheckpointEntry> loadedSkills = new ArrayList<>();
    private String userMessage;
    private int batchNumber;
    // 包含尚未得到结果的 assistant(tool_calls)，恢复时先补 tool 消息再请求模型。
    private List<LlmMessage> messages = new ArrayList<>();
    private List<ToolCall> pendingCalls = new ArrayList<>();
    private int currentRunStartIndex;
    private int completedToolRounds;
    private int completedRecoveryCalls;
    private int recoveredCharacters;
    private boolean summaryUsed;
    // 保存已用审查纠正次数，审批恢复不能获得新的纠正机会。
    private int answerReviewCorrections;
    // 两种短引用及编号生成器都属于同一个逻辑任务，不能因换线程而重建编号。
    private Map<String, String> recoveryReferences = new LinkedHashMap<>();
    private int nextRecoveryNumber;
    private List<MemoryExtractionTarget> memoryTargets = new ArrayList<>();
    private int nextMemoryNumber;
    // 保留已经发生的工具记录，后置记忆提取仍能防止重复写入。
    private List<ToolExecutionSnapshot> toolExecutions = new ArrayList<>();
    private List<String> executedToolNames = new ArrayList<>();
    private boolean toolHistoryComplete;
}
