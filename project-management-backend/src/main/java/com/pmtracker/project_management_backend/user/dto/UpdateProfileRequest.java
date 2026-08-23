package com.pmtracker.project_management_backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Границы те же, что в RegisterRequest, и по той же причине: колонки ФИО в users —
// VARCHAR(100) (V1__auth.sql).
public record UpdateProfileRequest(
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String patronymic
) {
}
