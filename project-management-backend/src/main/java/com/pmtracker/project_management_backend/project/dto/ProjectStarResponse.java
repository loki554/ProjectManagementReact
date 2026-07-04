package com.pmtracker.project_management_backend.project.dto;

public record ProjectStarResponse(
        long starCount,
        boolean starredByMe
) {
}
