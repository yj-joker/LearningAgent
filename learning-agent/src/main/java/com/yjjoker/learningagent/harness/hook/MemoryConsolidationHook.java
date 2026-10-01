package com.yjjoker.learningagent.harness.hook;

import com.yjjoker.learningagent.harness.memory.service.MemoryConsolidationScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Hook 调用统一入口完成轻量预检查；只有需要整理的范围才会进入后台队列。
@Component
@RequiredArgsConstructor
public class MemoryConsolidationHook implements AgentHook {
    private final MemoryConsolidationScheduler scheduler;

    // 暂停和异常也会触发 afterRun，只有正常完成的任务才提交整理检查。
    @Override
    public void afterRun(AgentRunContext context) {
        if (context.isCompleted() && context.isSuccessful() && !context.isWaitingApproval()) {
            // 普通记忆审批提交后也走这个入口，判断规则只维护一份。
            scheduler.request(context.getUserId(), context.getSessionId());
        }
    }
}
