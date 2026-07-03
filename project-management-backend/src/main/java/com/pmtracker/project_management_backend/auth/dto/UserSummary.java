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
        String avatarUrl = buildAvatarUrl(user);
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getLastName(),
                user.getFirstName(),
                user.getPatronymic(),
                avatarUrl
        );
    }

    /**
     * Путь всегда "/users/{id}/avatar" — сам по себе он не меняется между загрузками
     * новой аватарки, хотя файл на диске каждый раз новый (LocalFileStorageService
     * генерирует случайное имя). Без версии во фронтенде не было сигнала, что картинку
     * пора перезапросить (useAuthenticatedImage перезапрашивает только при смене url),
     * поэтому после повторной загрузки показывалась старая аватарка до перезагрузки
     * страницы. Добавляем ?v=<имя файла> — оно уникально на каждую загрузку и меняет url.
     */
    private static String buildAvatarUrl(User user) {
        String avatarPath = user.getAvatarPath();
        if (avatarPath == null) {
            return null;
        }
        String version = avatarPath.substring(avatarPath.lastIndexOf('/') + 1);
        return "/users/" + user.getId() + "/avatar?v=" + version;
    }
}
