package com.pmtracker.project_management_backend.common.exception;

public class TaskStatusConflictException extends RuntimeException {

    public TaskStatusConflictException() {
        super("Task status has changed since it was last loaded, refresh and try again");
    }
}
