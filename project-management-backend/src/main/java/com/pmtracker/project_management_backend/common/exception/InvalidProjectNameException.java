package com.pmtracker.project_management_backend.common.exception;

public class InvalidProjectNameException extends RuntimeException {

    public InvalidProjectNameException() {
        super("Project name must contain at least one letter or digit");
    }
}
