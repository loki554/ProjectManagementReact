package com.pmtracker.project_management_backend.auth.dto;

import com.pmtracker.project_management_backend.common.validation.MaxByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        // 43 символа Base64URL от 32 случайных байт; запас на случай смены формата — см. RefreshRequest.
        @NotBlank @Size(max = 128) String token,
        // Границы те же, что у пароля при регистрации: политика снизу, предел BCrypt сверху.
        @NotBlank
        @Size(min = 8, message = "Password must be at least 8 characters long")
        @MaxByteLength(value = 72, message = "Password must not exceed 72 bytes")
        String newPassword
) {
}
