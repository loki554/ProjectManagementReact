package com.pmtracker.project_management_backend.user.dto;

import com.pmtracker.project_management_backend.common.UsernameNormalizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Границы те же, что в RegisterRequest, и по той же причине: колонки ФИО в users —
// VARCHAR(100) (V1__auth.sql).
public record UpdateProfileRequest(
        // Никнейм здесь же, а не отдельным эндпоинтом: это такое же поле профиля, как
        // фамилия, и своя кнопка ему не нужна — в отличие от смены пароля, которая
        // разлогинивает, и настроек писем, которых семь штук.
        @NotBlank
        @Pattern(regexp = UsernameNormalizer.INPUT_PATTERN,
                message = "Username must be 3-30 characters: latin letters, digits, _ or -")
        String username,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String patronymic
) {
}
