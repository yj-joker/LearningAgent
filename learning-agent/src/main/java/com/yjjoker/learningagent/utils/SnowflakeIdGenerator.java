package com.yjjoker.learningagent.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * 不依赖第三方工具库的轻量级雪花 ID 生成器。
 *
 * <p>64 位 ID 的结构如下：
 *
 * <pre>
 * 0 | 41 位时间戳差值 | 10 位 workerId | 12 位序列号
 * </pre>
 *
 * <p>单个生成器实例是线程安全的。为了保证全局唯一，每个同时运行的应用实例必须使用不同的
 * {@code workerId}。
 */
@Component
public final class SnowflakeIdGenerator {

    /** 2020-01-01 00:00:00 Asia/Shanghai。ID 投入生产后不得修改。 */
    private static final long START_TIMESTAMP =
            Instant.parse("2019-12-31T16:00:00Z").toEpochMilli();

    private static final long TIMESTAMP_BITS = 41L;
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_TIMESTAMP_DELTA = (1L << TIMESTAMP_BITS) - 1;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    private static final long MAX_CLOCK_BACKWARD_MILLIS = 5L;

    private final long workerId;
    private final LongSupplier currentTimeMillis;

    private long sequence;
    private long lastTimestamp = -1L;

    /** 创建 Spring 单例。多实例部署时，每个实例必须配置不同的 snowflake.worker-id（0~1023）。 */
    @Autowired
    public SnowflakeIdGenerator(@Value("${snowflake.worker-id}") long workerId) {
        this(workerId, System::currentTimeMillis);
    }

    /** 包级私有的时钟注入点，用于稳定测试序列溢出和时钟回拨等边界情况。 */
    SnowflakeIdGenerator(long workerId, LongSupplier currentTimeMillis) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "workerId must be between 0 and " + MAX_WORKER_ID + ", but was " + workerId);
        }
        if (currentTimeMillis == null) {
            throw new IllegalArgumentException("currentTimeMillis must not be null");
        }

        this.workerId = workerId;
        this.currentTimeMillis = currentTimeMillis;
    }

    /** 生成下一个 ID。同一实例内严格递增，不同 workerId 之间只保证大致按时间有序。 */
    public synchronized long nextId() {
        long currentTimestamp = timeGen();

        if (currentTimestamp < lastTimestamp) {
            currentTimestamp = handleClockMovedBackwards(currentTimestamp);
        }

        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                currentTimestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }

        long timestampDelta = currentTimestamp - START_TIMESTAMP;
        validateTimestampDelta(timestampDelta, currentTimestamp);
        lastTimestamp = currentTimestamp;

        return (timestampDelta << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long handleClockMovedBackwards(long currentTimestamp) {
        long offset = lastTimestamp - currentTimestamp;
        if (offset > MAX_CLOCK_BACKWARD_MILLIS) {
            throw new IllegalStateException(
                    "Clock moved backwards by " + offset
                            + " ms; refusing to generate an ID until the clock catches up");
        }

        return waitUntil(lastTimestamp);
    }

    private void validateTimestampDelta(long timestampDelta, long currentTimestamp) {
        if (timestampDelta < 0) {
            throw new IllegalStateException(
                    "Current timestamp " + currentTimestamp
                            + " is earlier than Snowflake epoch " + START_TIMESTAMP);
        }
        if (timestampDelta > MAX_TIMESTAMP_DELTA) {
            throw new IllegalStateException("Snowflake timestamp bits have been exhausted");
        }
    }

    private long waitUntil(long targetTimestamp) {
        long currentTimestamp = timeGen();
        while (currentTimestamp < targetTimestamp) {
            try {
                Thread.sleep(Math.min(targetTimestamp - currentTimestamp, 1L));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the clock", exception);
            }
            currentTimestamp = timeGen();
        }
        return currentTimestamp;
    }

    private long timeGen() {
        return currentTimeMillis.getAsLong();
    }
}
