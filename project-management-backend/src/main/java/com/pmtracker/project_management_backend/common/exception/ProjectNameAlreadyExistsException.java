package com.pmtracker.project_management_backend.common.exception;

public class ProjectNameAlreadyExistsException extends RuntimeException {

    public ProjectNameAlreadyExistsException() {
        super("A project with this name already exists");
    }
}
