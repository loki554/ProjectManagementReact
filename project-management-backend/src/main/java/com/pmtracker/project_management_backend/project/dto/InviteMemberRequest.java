package com.pmtracker.project_management_backend.project.dto;

import com.pmtracker.project_management_backend.project.ProjectRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InviteMemberRequest(
        @Email @NotBlank String email,
        @NotNull ProjectRole role
) {
}
