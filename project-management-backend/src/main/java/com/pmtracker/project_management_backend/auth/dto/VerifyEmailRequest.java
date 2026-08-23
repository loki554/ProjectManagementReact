package com.pmtracker.project_management_backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Токен подтверждения — это UUID (AuthService.parseToken), то есть ровно 36 символов;
// всё, что длиннее, заведомо невалидно и отсекается до похода в БД.
public record VerifyEmailRequest(@NotBlank @Size(max = 36) String token) {
}
