package com.pmtracker.project_management_backend.common.exception;

public class DuplicateSprintNameException extends RuntimeException {

    public DuplicateSprintNameException() {
        super("A sprint with this name already exists in the project");
    }
}
