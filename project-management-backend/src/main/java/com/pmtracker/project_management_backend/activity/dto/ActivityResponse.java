package com.pmtracker.project_management_backend.activity.dto;

import com.pmtracker.project_management_backend.activity.ProjectActivity;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ActivityResponse(
        UUID id,
        String type,
        // null — автор события удалил аккаунт.
        UserSummary actor,
        // null — событие уровня проекта либо задача уже удалена (снапшот остаётся в payload);
        // фронтенд рисует ссылку на задачу только при ненулевом taskId.
        UUID taskId,
        Map<String, Object> payload,
        Instant createdAt
) {
    public static ActivityResponse from(ProjectActivity activity) {
        return new ActivityResponse(
                activity.getId(),
                activity.getType(),
                activity.getActor() != null ? UserSummary.from(activity.getActor()) : null,
                activity.getTask() != null ? activity.getTask().getId() : null,
                activity.getPayload(),
                activity.getCreatedAt()
        );
    }
}
