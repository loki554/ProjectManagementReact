package com.pmtracker.project_management_backend.common.exception;

public class AttachmentNotFoundException extends RuntimeException {

    public AttachmentNotFoundException() {
        super("Attachment not found");
    }
}
