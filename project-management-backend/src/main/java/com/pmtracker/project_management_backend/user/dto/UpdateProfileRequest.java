package com.pmtracker.project_management_backend.user.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(
        @NotBlank String lastName,
        @NotBlank String firstName,
        String patronymic
) {
}
