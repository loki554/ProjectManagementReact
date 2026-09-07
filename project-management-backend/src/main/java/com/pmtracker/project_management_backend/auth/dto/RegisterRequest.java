package com.pmtracker.project_management_backend.auth.dto;

import com.pmtracker.project_management_backend.common.UsernameNormalizer;
import com.pmtracker.project_management_backend.common.validation.MaxByteLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Верхние границы @Size здесь не косметика, а защита от 500-й: колонки в users — это
// VARCHAR(255) для email и VARCHAR(100) для ФИО (V1__auth.sql), и без @Size строка длиннее
// доезжала до БД, падала DataIntegrityViolationException и уходила в handleUnexpected как
// INTERNAL_ERROR вместо честного 400 с указанием поля.
public record RegisterRequest(
        @Email @NotBlank @Size(max = 255) String email,
        // Никнейм обязателен с самого начала (в отличие от «заполните потом в профиле»):
        // придуманный за человека он всё равно был бы никому не известен, а половина
        // проекта без никнейма означала бы, что @упоминания работают через раз.
        // Шаблон допускает заглавные (INPUT_PATTERN), хотя хранится никнейм только в
        // нижнем регистре: валидация идёт до нормализации, и с канонической маской
        // набранное «Ivanov» отвечало бы 400 вместо того, чтобы стать «ivanov».
        @NotBlank
        @Pattern(regexp = UsernameNormalizer.INPUT_PATTERN,
                message = "Username must be 3-30 characters: latin letters, digits, _ or -")
        String username,
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
