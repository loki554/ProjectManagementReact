package com.pmtracker.project_management_backend.user.dto;

import com.pmtracker.project_management_backend.common.validation.MaxByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        // У текущего пароля только верхняя граница: нижнюю проверять бессмысленно (он либо
        // совпадает с сохранённым, либо нет), а требование min = 8 к нему сломало бы смену
        // пароля тем, кто регистрировался до появления этого правила.
        @NotBlank @MaxByteLength(value = 72, message = "Password must not exceed 72 bytes") String currentPassword,
        @NotBlank
        @Size(min = 8, message = "Password must be at least 8 characters long")
        @MaxByteLength(value = 72, message = "Password must not exceed 72 bytes")
        String newPassword
) {
}
