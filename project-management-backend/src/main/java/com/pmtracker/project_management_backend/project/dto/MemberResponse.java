package com.pmtracker.project_management_backend.project.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectRole;

import java.time.Instant;
import java.util.UUID;

public record MemberResponse(
        UUID userId,
        String email,
        String lastName,
        String firstName,
        String patronymic,
        String avatarUrl,
        ProjectRole role,
        Instant joinedAt
) {
    public static MemberResponse from(ProjectMember member) {
        UserSummary user = UserSummary.from(member.getUser());
        return new MemberResponse(
                user.id(),
                user.email(),
                user.lastName(),
                user.firstName(),
                user.patronymic(),
                user.avatarUrl(),
                member.getRole(),
                member.getJoinedAt()
        );
    }
}
