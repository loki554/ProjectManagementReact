package com.pmtracker.project_management_backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        boolean archived
) {
}
