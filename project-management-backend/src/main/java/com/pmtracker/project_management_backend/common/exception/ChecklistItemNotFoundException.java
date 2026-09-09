package com.pmtracker.project_management_backend.common.exception;

public class ChecklistItemNotFoundException extends RuntimeException {

    public ChecklistItemNotFoundException() {
        super("Checklist item not found");
    }
}
