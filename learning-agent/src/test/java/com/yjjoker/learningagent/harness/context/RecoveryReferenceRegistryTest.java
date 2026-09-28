package com.yjjoker.learningagent.harness.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("恢复引用注册表测试")
class RecoveryReferenceRegistryTest {
    private static final String RUN_A = "00000000-0000-0000-0000-000000000001";
    private static final String RUN_B = "00000000-0000-0000-0000-000000000002";

    // 删除旧引用后仍继续递增，不能把旧编号重新指给另一份结果。
    @Test
    @DisplayName("摘要裁剪后旧引用失效且新引用不会复用旧编号")
    void shouldExpireRemovedReferences() {
        RecoveryReferenceRegistry registry = new RecoveryReferenceRegistry(RUN_A);

        assertEquals("result_1_1", registry.register("call_old"));
        assertEquals(1, registry.retainToolCallIds(List.of()));
        assertNull(registry.resolve("result_1_1"));

        assertEquals("result_1_2", registry.register("call_recent"));
        assertEquals("call_recent", registry.resolve("result_1_2"));
        assertNull(registry.resolve("result_1_1"));
    }

    // 两个任务都从序号 1 开始，完整引用仍不同；旧引用必须查不到，而不是读取新资料。
    @Test
    void rejectsAnotherTasksReferenceEvenWhenNumbersMatch() {
        RecoveryReferenceRegistry first = new RecoveryReferenceRegistry(RUN_A);
        RecoveryReferenceRegistry second = new RecoveryReferenceRegistry(RUN_B);
        String oldRef = first.register("java_material");
        String newRef = second.register("database_material");
        assertNotEquals(oldRef, newRef);
        assertNull(second.resolve(oldRef));
        assertNull(second.resolve("result_1"));
        assertEquals("database_material", second.resolve(newRef));
    }

    // 新建 Java 对象恢复同一个任务时，旧引用和递增位置都保持不变。
    @Test
    void restoresSameTaskAndRejectsForeignSnapshot() {
        RecoveryReferenceRegistry source = new RecoveryReferenceRegistry(RUN_A);
        String ref = source.register("first");
        RecoveryReferenceRegistry resumed = new RecoveryReferenceRegistry(RUN_A);
        resumed.restore(source.snapshot(), source.nextNumber());
        assertEquals(ref, resumed.register("first"));
        assertEquals("result_1_2", resumed.register("second"));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryReferenceRegistry(RUN_B)
                .restore(source.snapshot(), source.nextNumber()));
    }

    // 只在恢复旧检查点时保留旧编号；此后新分配的引用仍带任务标识。
    @Test
    void restoresLegacyCheckpointWithoutGeneratingNewLegacyAliases() {
        RecoveryReferenceRegistry registry = new RecoveryReferenceRegistry(RUN_A);
        registry.restore(Map.of("result_2", "legacy"), 8);
        assertEquals("legacy", registry.resolve("result_2"));
        assertEquals("result_1_8", registry.register("new"));
    }
}
