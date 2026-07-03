package com.pmtracker.project_management_backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8, message = "Пароль должен быть не короче 8 символов") String password,
        @NotBlank String lastName,
        @NotBlank String firstName,
        String patronymic
) {
}
