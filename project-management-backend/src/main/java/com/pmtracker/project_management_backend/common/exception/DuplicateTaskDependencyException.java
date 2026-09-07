package com.pmtracker.project_management_backend.common.exception;

public class DuplicateTaskDependencyException extends RuntimeException {

    public DuplicateTaskDependencyException() {
        super("This dependency already exists");
    }
}
