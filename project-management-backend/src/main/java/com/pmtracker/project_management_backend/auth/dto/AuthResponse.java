package com.pmtracker.project_management_backend.auth.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserSummary user
) {
}
