package com.pmtracker.project_management_backend.common.exception;

public class NotTimeLogOwnerException extends RuntimeException {

    public NotTimeLogOwnerException() {
        super("Only the author of the time log or a project OWNER/ADMIN can delete it");
    }
}
