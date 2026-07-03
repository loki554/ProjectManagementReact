package com.pmtracker.project_management_backend.project.dto;

import com.pmtracker.project_management_backend.project.ProjectRole;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(
        @NotNull ProjectRole role
) {
}
