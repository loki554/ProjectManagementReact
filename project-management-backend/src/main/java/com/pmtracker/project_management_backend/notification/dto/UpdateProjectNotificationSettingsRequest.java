package com.pmtracker.project_management_backend.notification.dto;

import com.pmtracker.project_management_backend.notification.ProjectNotificationMode;
import jakarta.validation.constraints.NotNull;

/**
 * Выбор режима уведомлений по проекту (4.16).
 * <p>
 * {@code @NotNull} обязателен по той же причине, что у {@code UpdateNotificationSettingsRequest}:
 * без него опечатка в имени поля означала бы не 400, а молча применённый {@code null}, —
 * а здесь это ещё и настройка, ошибку в которой человек заметит только по неприходящим
 * письмам через неделю.
 */
public record UpdateProjectNotificationSettingsRequest(@NotNull ProjectNotificationMode mode) {
}
