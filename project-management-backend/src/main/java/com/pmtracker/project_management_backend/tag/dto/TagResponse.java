package com.pmtracker.project_management_backend.tag.dto;

import com.pmtracker.project_management_backend.tag.Tag;

import java.time.Instant;
import java.util.UUID;

public record TagResponse(
        UUID id,
        UUID projectId,
        String name,
        String color,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static TagResponse from(Tag tag) {
        return new TagResponse(
                tag.getId(),
                tag.getProject().getId(),
                tag.getName(),
                tag.getColor(),
                tag.getCreatedBy().getId(),
                tag.getCreatedAt(),
                tag.getUpdatedAt()
        );
    }
}
