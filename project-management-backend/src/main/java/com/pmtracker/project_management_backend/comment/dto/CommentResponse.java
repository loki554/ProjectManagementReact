package com.pmtracker.project_management_backend.comment.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.comment.TaskComment;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code editedAt} = null означает «не редактировали» (4.4) — интерфейс показывает пометку
 * «изменено» ровно тогда, когда значение пришло. Отдавать вместо null сам {@code createdAt}
 * было бы удобнее коду и хуже людям: пометка появилась бы на каждом комментарии.
 */
public record CommentResponse(
        UUID id,
        UUID taskId,
        UserSummary author,
        String body,
        Instant createdAt,
        Instant editedAt
) {
    public static CommentResponse from(TaskComment comment) {
        return new CommentResponse(
                comment.getId(),
                comment.getTask().getId(),
                UserSummary.from(comment.getAuthor()),
                comment.getBody(),
                comment.getCreatedAt(),
                comment.getEditedAt()
        );
    }
}
