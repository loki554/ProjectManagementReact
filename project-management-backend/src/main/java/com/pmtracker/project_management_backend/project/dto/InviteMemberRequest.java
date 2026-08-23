package com.pmtracker.project_management_backend.project.dto;

import com.pmtracker.project_management_backend.project.ProjectRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InviteMemberRequest(
        @Email @NotBlank @Size(max = 255) String email,
        @NotNull ProjectRole role
) {
}
