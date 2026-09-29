package com.yjjoker.learningagent.notification;

import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 把事务提交与网络发送分开，通知失败不能让已经成功的审批变成失败。
@Service
@Slf4j
public class ApprovalNotifier {
    private final ApprovalWebSocketHandler sockets;
    private final Executor executor;

    // 使用单独的小线程池，慢连接不占聊天请求或记忆整理的线程。
    public ApprovalNotifier(ApprovalWebSocketHandler sockets,
                            @Qualifier("approvalNotificationExecutor") Executor executor) {
        this.sockets = sockets;
        this.executor = executor;
    }

    // 必须在审批事务内调用；只登记通知，不提前向浏览器宣告成功。
    public void changedAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            // 缺少事务时宁可漏通知，也不能推送尚未持久化的数据。
            log.warn("审批通知未登记：缺少事务，userId={}", userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            // 事务回滚不会执行该回调；后台任务显式携带身份，不依赖 ThreadLocal。
            @Override
            public void afterCommit() {
                dispatch(userId);
            }
        });
    }

    // 有界队列满了就放弃本次提示；数据库仍保留结果，可手动刷新或重连补查。
    private void dispatch(Long userId) {
        try {
            executor.execute(() -> sockets.notifyUser(userId));
        } catch (RuntimeException exception) {
            log.warn("审批通知暂未派发，请刷新读取，userId={}，errorType={}", userId,
                    exception.getClass().getSimpleName());
        }
    }
}
