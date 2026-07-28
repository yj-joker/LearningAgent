package com.yjjoker.learningagent.page;

import lombok.Data;
import lombok.Getter;

import java.util.List;

@Data
public  class PageResult<T> {

    private final int page;
    private final int size;
    private final long totalElements;
    private final long totalPages;
    private final boolean hasPrevious;
    private final boolean hasNext;
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

    public static <T> PageResult<T> of(
            PageRequest request,
            long totalElements,
            List<T> items
    ) {
        long totalPages = totalElements / request.getSize();
        if (totalElements % request.getSize() != 0) {
            totalPages++;
        }

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
