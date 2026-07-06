package com.pmtracker.project_management_backend.common.exception;

public class NotCommentOwnerException extends RuntimeException {

    public NotCommentOwnerException() {
        super("Only the author of the comment or a project OWNER/ADMIN can delete it");
    }
}
