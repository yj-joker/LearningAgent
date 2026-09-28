package com.yjjoker.learningagent.harness.model;

import java.math.BigInteger;
import java.util.UUID;

// 给两类临时引用加任务标识；完整保留 UUID 信息，不截取几位后冒险重名。
public final class TaskReferenceScope {
    // 工具类只提供转换方法，不需要创建对象。
    private TaskReferenceScope() {}

    // 将完整任务 UUID 转成较短的 36 进制文本；同一任务恢复后仍得到相同标识。
    public static String fromRunId(String runId) {
        String hex = UUID.fromString(runId).toString().replace("-", "");
        return new BigInteger(hex, 16).toString(36);
    }

    // 校验服务端检查点中的编号，兼容升级前已暂停任务的旧引用，但不接受其他任务的新引用。
    public static int restoredNumber(String reference, String kind, String scope) {
        if (reference == null) {
            throw new IllegalArgumentException("检查点引用不能为空");
        }
        String prefix = kind + "_" + scope + "_";
        String number;
        if (reference.startsWith(prefix)) {
            number = reference.substring(prefix.length());
        } else if (reference.startsWith(kind + "_")) {
            // 仅允许旧的纯数字后缀；新任务不会生成这种无任务标识的引用。
            number = reference.substring(kind.length() + 1);
        } else {
            throw new IllegalArgumentException("检查点引用类型不匹配");
        }
        if (!number.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("检查点引用不属于当前任务或编号不合法");
        }
        return Integer.parseInt(number);
    }
}
