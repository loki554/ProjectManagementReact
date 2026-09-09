package com.pmtracker.project_management_backend.notification.dto;

import com.pmtracker.project_management_backend.notification.ProjectNotificationMode;

/**
 * Режим уведомлений текущего пользователя по проекту (4.16).
 * <p>
 * Одно поле, а не голый enum строкой: у настройки по проекту заведомо появятся соседи —
 * хотя бы «с какого момента слежу» или счётчик наблюдателей, как у звезды, — и менять
 * форму ответа с примитива на объект потом дороже, чем завести объект сразу.
 */
public record ProjectNotificationSettingsResponse(ProjectNotificationMode mode) {
}
