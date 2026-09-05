package com.pmtracker.project_management_backend.notification.dto;

import com.pmtracker.project_management_backend.notification.NotificationDeliveryMode;
import jakarta.validation.constraints.NotNull;

/**
 * Полная замена настроек, а не частичное обновление, — несмотря на PATCH (тот же приём, что
 * у {@code UpdateProfileRequest}). Форма настроек показывает все переключатели сразу и
 * отправляет их все: «прислали половину, остальное оставьте как было» здесь ничего не
 * упрощает, а трактовку отсутствующего поля усложняет.
 * <p>
 * Обёртки Boolean, а не примитивы: у примитива отсутствующее в JSON поле молча стало бы
 * {@code false}, то есть опечатка в имени поля выключала бы человеку уведомления. С
 * {@code @NotNull} тот же запрос честно отвечает 400.
 */
public record UpdateNotificationSettingsRequest(
        @NotNull Boolean emailEnabled,
        @NotNull NotificationDeliveryMode mode,
        @NotNull Boolean taskAssigned,
        @NotNull Boolean taskComment,
        @NotNull Boolean taskDueSoon,
        @NotNull Boolean taskOverdue
) {
}
