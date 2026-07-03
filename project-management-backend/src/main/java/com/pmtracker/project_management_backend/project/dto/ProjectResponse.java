package com.pmtracker.project_management_backend.project.dto;

import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectRole;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String description,
        boolean archived,
        ProjectRole myRole,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectResponse from(Project project, ProjectRole myRole) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.isArchived(),
                myRole,
                project.getCreatedBy().getId(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
