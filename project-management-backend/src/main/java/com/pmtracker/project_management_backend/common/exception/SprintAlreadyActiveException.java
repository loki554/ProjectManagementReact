package com.pmtracker.project_management_backend.common.exception;

public class SprintAlreadyActiveException extends RuntimeException {

    public SprintAlreadyActiveException() {
        super("Another sprint of this project is already active");
    }
}
