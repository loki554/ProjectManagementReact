package com.pmtracker.project_management_backend.notification.dto;

import com.pmtracker.project_management_backend.notification.NotificationDeliveryMode;
import com.pmtracker.project_management_backend.notification.NotificationSettings;

/**
 * Настройки email-уведомлений (4.3). Плоские поля по типам, а не словарь: набор типов задан
 * кодом и на обеих сторонах, и фронтенду с плоским объектом не нужно гадать, какие ключи
 * придут (см. ProfilePage → NotificationSettingsSection).
 */
public record NotificationSettingsResponse(
        boolean emailEnabled,
        NotificationDeliveryMode mode,
        boolean taskAssigned,
        boolean taskComment,
        boolean taskMention,
        boolean taskDueSoon,
        boolean taskOverdue
) {
    public static NotificationSettingsResponse from(NotificationSettings settings) {
        return new NotificationSettingsResponse(
                settings.isEmailEnabled(),
                settings.getMode(),
                settings.isTaskAssigned(),
                settings.isTaskComment(),
                settings.isTaskMention(),
                settings.isTaskDueSoon(),
                settings.isTaskOverdue());
    }
}
