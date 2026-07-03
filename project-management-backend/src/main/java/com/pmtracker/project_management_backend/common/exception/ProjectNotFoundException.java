package com.pmtracker.project_management_backend.common.exception;

public class ProjectNotFoundException extends RuntimeException {

    public ProjectNotFoundException() {
        super("Проект не найден");
    }
}
