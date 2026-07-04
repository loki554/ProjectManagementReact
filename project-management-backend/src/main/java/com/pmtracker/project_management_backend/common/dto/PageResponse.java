package com.pmtracker.project_management_backend.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
