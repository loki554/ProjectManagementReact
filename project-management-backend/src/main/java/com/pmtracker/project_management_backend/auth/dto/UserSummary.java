package com.pmtracker.project_management_backend.auth.dto;

import com.pmtracker.project_management_backend.auth.User;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String lastName,
        String firstName,
        String patronymic,
        String avatarUrl
) {
    public static UserSummary from(User user) {
        String avatarUrl = user.getAvatarPath() != null ? "/users/" + user.getId() + "/avatar" : null;
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getLastName(),
                user.getFirstName(),
                user.getPatronymic(),
                avatarUrl
        );
    }
}
