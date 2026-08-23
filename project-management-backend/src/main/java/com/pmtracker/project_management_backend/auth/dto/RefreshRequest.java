package com.pmtracker.project_management_backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 64 случайных байта в Base64URL без паддинга (AuthService.createRefreshToken) — это 86
// символов. Запас до 128 оставлен на случай смены формата, но не до бесконечности: без
// границы на /auth/refresh можно было прислать тело любого размера и заставить сервер
// считать по нему SHA-256.
public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {
}
