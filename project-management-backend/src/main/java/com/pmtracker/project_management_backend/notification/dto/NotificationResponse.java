package com.pmtracker.project_management_backend.notification.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.notification.Notification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String type,
        // null — системное уведомление (task_due_soon/task_overdue) либо автор удалил аккаунт.
        UserSummary actor,
        // null — задача уже удалена; фронтенд рисует ссылку только при ненулевом taskId
        // (тот же приём, что ActivityResponse.taskId).
        UUID taskId,
        Map<String, Object> payload,
        boolean read,
        Instant createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getActor() != null ? UserSummary.from(notification.getActor()) : null,
                notification.getTask() != null ? notification.getTask().getId() : null,
                notification.getPayload(),
                notification.getReadAt() != null,
                notification.getCreatedAt()
        );
    }
}
