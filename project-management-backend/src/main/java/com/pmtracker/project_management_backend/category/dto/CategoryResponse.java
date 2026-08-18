package com.pmtracker.project_management_backend.category.dto;

import com.pmtracker.project_management_backend.category.Category;

import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        UUID projectId,
        String name,
        // Сколько задач сейчас в этой категории — страница управления показывает счётчик,
        // чтобы было видно, что именно потеряет привязку при удалении.
        long taskCount,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static CategoryResponse from(Category category, long taskCount) {
        return new CategoryResponse(
                category.getId(),
                category.getProject().getId(),
                category.getName(),
                taskCount,
                category.getCreatedBy().getId(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }
}
