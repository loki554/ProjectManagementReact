package com.pmtracker.project_management_backend.common.exception;

public class NotAttachmentOwnerException extends RuntimeException {

    public NotAttachmentOwnerException() {
        super("Only the uploader of the attachment or a project OWNER/ADMIN can delete it");
    }
}
