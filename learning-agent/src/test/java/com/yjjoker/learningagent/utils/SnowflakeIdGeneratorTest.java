package com.yjjoker.learningagent.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("雪花 ID 生成器测试")
class SnowflakeIdGeneratorTest {

    private static final long TEST_TIMESTAMP =
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    @Test
    @DisplayName("同一毫秒内应通过递增序列号生成不同 ID")
    void shouldIncrementSequenceWithinSameMillisecond() {
        SnowflakeIdGenerator generator =
                new SnowflakeIdGenerator(1, () -> TEST_TIMESTAMP);

        long firstId = generator.nextId();
        long secondId = generator.nextId();
        long thirdId = generator.nextId();

        assertEquals(firstId + 1, secondId);
        assertEquals(secondId + 1, thirdId);
    }

    @Test
    @DisplayName("序列号用尽后应等待下一毫秒并继续递增")
    void shouldWaitForNextMillisecondWhenSequenceIsExhausted() {
        AtomicInteger clockReads = new AtomicInteger();
        LongSupplier clock = () ->
                clockReads.incrementAndGet() <= 4097
                        ? TEST_TIMESTAMP
                        : TEST_TIMESTAMP + 1;
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, clock);

        long previousId = generator.nextId();
        for (int i = 1; i <= 4096; i++) {
            long currentId = generator.nextId();
            assertTrue(currentId > previousId);
            previousId = currentId;
        }
    }

    @Test
    @DisplayName("不超过五毫秒的时钟回拨应等待时钟追平")
    void shouldWaitWhenClockMovesBackwardsSlightly() {
        LongSupplier clock = timestamps(TEST_TIMESTAMP, TEST_TIMESTAMP - 3, TEST_TIMESTAMP);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, clock);

        long firstId = generator.nextId();
        long secondId = generator.nextId();

        assertTrue(secondId > firstId);
    }

    @Test
    @DisplayName("超过五毫秒的时钟回拨应拒绝生成 ID")
    void shouldFailWhenClockMovesBackwardsTooMuch() {
        LongSupplier clock = timestamps(TEST_TIMESTAMP, TEST_TIMESTAMP - 6);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, clock);

        generator.nextId();

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, generator::nextId);
        assertTrue(exception.getMessage().contains("6 ms"));
    }

    @Test
    @DisplayName("workerId 必须位于零到 1023 之间")
    void shouldRejectInvalidWorkerId() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
    }

    @Test
    @DisplayName("多个线程共享同一生成器时不应产生重复 ID")
    void shouldGenerateUniqueIdsConcurrently() throws Exception {
        int threadCount = 8;
        int idsPerThread = 5_000;
        int expectedIdCount = threadCount * idsPerThread;

        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(7);
        Set<Long> ids = ConcurrentHashMap.newKeySet(expectedIdCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int j = 0; j < idsPerThread; j++) {
                        ids.add(generator.nextId());
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(expectedIdCount, ids.size());
    }

    private static LongSupplier timestamps(long... values) {
        AtomicInteger index = new AtomicInteger();
        return () -> values[Math.min(index.getAndIncrement(), values.length - 1)];
    }
}
