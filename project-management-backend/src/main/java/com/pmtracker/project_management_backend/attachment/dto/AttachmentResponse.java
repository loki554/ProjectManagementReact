package com.pmtracker.project_management_backend.attachment.dto;

import com.pmtracker.project_management_backend.attachment.Attachment;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        UUID taskId,
        UserSummary uploadedBy,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Instant createdAt
) {
    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getTask().getId(),
                UserSummary.from(attachment.getUploadedBy()),
                attachment.getOriginalFilename(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getCreatedAt()
        );
    }
}
