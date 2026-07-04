package com.pmtracker.project_management_backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank
        @Size(max = 255)
        // Только печатаемая латиница/ASCII — имя проекта используется для генерации slug
        // в URL (ProjectService.slugify), кириллица и т.п. дали бы пустой или нечитаемый slug.
        @Pattern(regexp = "^[\\x20-\\x7E]+$", message = "Project name must contain only Latin letters, digits, and standard punctuation")
        String name,
        String description
) {
}
