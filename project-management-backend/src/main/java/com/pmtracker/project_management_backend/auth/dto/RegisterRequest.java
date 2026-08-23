package com.pmtracker.project_management_backend.auth.dto;

import com.pmtracker.project_management_backend.common.validation.MaxByteLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Верхние границы @Size здесь не косметика, а защита от 500-й: колонки в users — это
// VARCHAR(255) для email и VARCHAR(100) для ФИО (V1__auth.sql), и без @Size строка длиннее
// доезжала до БД, падала DataIntegrityViolationException и уходила в handleUnexpected как
// INTERNAL_ERROR вместо честного 400 с указанием поля.
public record RegisterRequest(
        @Email @NotBlank @Size(max = 255) String email,
        // Нижняя граница — политика паролей, верхняя — жёсткий предел BCrypt (см. @MaxByteLength).
        @NotBlank
        @Size(min = 8, message = "Password must be at least 8 characters long")
        @MaxByteLength(value = 72, message = "Password must not exceed 72 bytes")
        String password,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String patronymic
) {
}
