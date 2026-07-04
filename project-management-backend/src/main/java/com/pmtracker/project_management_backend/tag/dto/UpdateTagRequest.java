package com.pmtracker.project_management_backend.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateTagRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color
) {
}
