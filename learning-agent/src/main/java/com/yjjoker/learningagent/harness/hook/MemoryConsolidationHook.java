package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Hook 只通知整理调度器，不在聊天结束回调中调用模型或执行合并。
@Component
@RequiredArgsConstructor
public class MemoryConsolidationHook implements AgentHook {
    private final MemoryConsolidationScheduler scheduler;

    // 暂停和异常也会触发 afterRun，只有正常完成的任务才提交整理检查。
    @Override
    public void afterRun(AgentRunContext context) {
        if (context.isCompleted() && context.isSuccessful() && !context.isWaitingApproval()) {
            scheduler.request(context.getUserId(), context.getSessionId());
        }
    }
}
