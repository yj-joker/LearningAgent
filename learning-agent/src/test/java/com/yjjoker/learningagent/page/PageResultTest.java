package com.yjjoker.learningagent.page;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("分页模型测试")
class PageResultTest {

    @Test
    @DisplayName("分页请求应提供合理默认值并正确计算偏移量")
    void shouldProvideDefaultsAndCalculateOffset() {
        PageRequest request = new PageRequest();

        assertEquals(1, request.getPage());
        assertEquals(20, request.getSize());
        assertEquals(0, request.offset());

        request.setPage(3);
        request.setSize(25);
        assertEquals(50, request.offset());
    }

    @Test
    @DisplayName("没有数据时总页数和翻页标记都应为零或 false")
    void shouldRepresentEmptyResult() {
        PageRequest request = new PageRequest();

        PageResult<String> result = PageResult.of(request, 0, List.of());

        assertEquals(0, result.getTotalPages());
        assertFalse(result.isHasPrevious());
        assertFalse(result.isHasNext());
        assertEquals(List.of(), result.getItems());
    }

    @Test
    @DisplayName("分页结果中的数据列表应不可被调用方修改")
    void shouldReturnImmutableItems() {
        PageRequest request = new PageRequest();
        PageResult<String> result = PageResult.of(request, 1, List.of("alice"));

        assertThrows(UnsupportedOperationException.class, () -> result.getItems().add("bob"));
    }
}
