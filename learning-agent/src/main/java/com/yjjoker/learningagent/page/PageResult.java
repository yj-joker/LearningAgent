package com.yjjoker.learningagent.page;

import lombok.Data;
import lombok.Getter;

import java.util.List;

@Data
public  class PageResult<T> {

    private final int page;
    private final int size;
    private final long totalElements;// 总元素数
    private final long totalPages;// 总页数
    private final boolean hasPrevious;// 是否有上一页
    private final boolean hasNext;// 是否有下一页
    private final List<T> items;

    private PageResult(
            int page,
            int size,
            long totalElements,
            long totalPages,
            boolean hasPrevious,
            boolean hasNext,
            List<T> items
    ) {
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.hasPrevious = hasPrevious;
        this.hasNext = hasNext;
        this.items = List.copyOf(items);
    }
    // 构建分页结果
    public static <T> PageResult<T> of(
            PageRequest request,
            long totalElements,
            List<T> items
    ) {
        // 计算总页数，如果有余数则加一
        long totalPages = totalElements / request.getSize();
        if (totalElements % request.getSize() != 0) {
            totalPages++;
        }
        //返回构建结果
        return new PageResult<>(
                request.getPage(),
                request.getSize(),
                totalElements,
                totalPages,
                totalPages > 0 && request.getPage() > 1,
                request.getPage() < totalPages,
                items
        );
    }
}
