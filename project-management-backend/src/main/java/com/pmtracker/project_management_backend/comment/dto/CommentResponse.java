package com.pmtracker.project_management_backend.comment.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.comment.TaskComment;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        UUID taskId,
        UserSummary author,
        String body,
        Instant createdAt
) {
    public static CommentResponse from(TaskComment comment) {
        return new CommentResponse(
                comment.getId(),
                comment.getTask().getId(),
                UserSummary.from(comment.getAuthor()),
                comment.getBody(),
                comment.getCreatedAt()
        );
    }
}
