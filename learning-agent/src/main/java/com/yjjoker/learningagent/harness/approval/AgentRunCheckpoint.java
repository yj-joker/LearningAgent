package com.yjjoker.learningagent.harness.approval;

import com.yjjoker.learningagent.harness.llm.model.LlmMessage;
import com.yjjoker.learningagent.harness.llm.model.ToolCall;
import com.yjjoker.learningagent.harness.memory.model.MemoryExtractionTarget;
import com.yjjoker.learningagent.harness.model.AgentMode;
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
