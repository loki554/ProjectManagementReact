package com.pmtracker.project_management_backend.category.dto;

import com.pmtracker.project_management_backend.category.Category;

import java.util.UUID;

public record CategorySummary(
        UUID id,
        String name
) {
    public static CategorySummary from(Category category) {
        return new CategorySummary(category.getId(), category.getName());
    }
}
