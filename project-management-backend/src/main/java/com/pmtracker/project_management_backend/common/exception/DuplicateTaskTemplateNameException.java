package com.pmtracker.project_management_backend.common.exception;

public class DuplicateTaskTemplateNameException extends RuntimeException {

    public DuplicateTaskTemplateNameException() {
        super("A template with this name already exists in the project");
    }
}
