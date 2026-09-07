package com.pmtracker.project_management_backend.common.exception;

public class TaskDependencyProjectMismatchException extends RuntimeException {

    public TaskDependencyProjectMismatchException() {
        super("Both tasks must belong to the same project");
    }
}
