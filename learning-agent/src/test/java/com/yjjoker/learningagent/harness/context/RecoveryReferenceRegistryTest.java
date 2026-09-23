package com.yjjoker.learningagent.harness.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("恢复引用注册表测试")
class RecoveryReferenceRegistryTest {

    @Test
    @DisplayName("摘要裁剪后旧引用失效且新引用不会复用旧编号")
    void shouldExpireRemovedReferences() {
        RecoveryReferenceRegistry registry = new RecoveryReferenceRegistry();

        assertEquals("result_1", registry.register("call_old"));
        assertEquals(1, registry.retainToolCallIds(List.of()));
        assertNull(registry.resolve("result_1"));

        assertEquals("result_2", registry.register("call_recent"));
        assertEquals("call_recent", registry.resolve("result_2"));
        assertNull(registry.resolve("result_1"));
    }
}
