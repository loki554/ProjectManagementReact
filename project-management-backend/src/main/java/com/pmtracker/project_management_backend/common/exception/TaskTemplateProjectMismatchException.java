package com.pmtracker.project_management_backend.common.exception;

public class TaskTemplateProjectMismatchException extends RuntimeException {

    public TaskTemplateProjectMismatchException() {
        super("Task template belongs to another project");
    }
}
