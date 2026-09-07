package com.pmtracker.project_management_backend.common.exception;

public class TaskDependencyNotFoundException extends RuntimeException {

    public TaskDependencyNotFoundException() {
        super("Dependency not found");
    }
}
