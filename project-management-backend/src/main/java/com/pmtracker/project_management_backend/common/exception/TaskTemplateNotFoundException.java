package com.pmtracker.project_management_backend.common.exception;

public class TaskTemplateNotFoundException extends RuntimeException {

    public TaskTemplateNotFoundException() {
        super("Task template not found");
    }
}
