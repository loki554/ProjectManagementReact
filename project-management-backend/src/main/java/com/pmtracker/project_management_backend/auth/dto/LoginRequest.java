package com.pmtracker.project_management_backend.auth.dto;

import com.pmtracker.project_management_backend.common.validation.MaxByteLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Email @NotBlank @Size(max = 255) String email,
        // Те же границы, что и в RegisterRequest: пароль длиннее 72 байт зарегистрировать
        // нельзя, значит и проверять его нечего — отсекаем до BCrypt, чтобы не гонять
        // произвольного размера тело через хеширование.
        @NotBlank @MaxByteLength(value = 72, message = "Password must not exceed 72 bytes") String password
) {
}
