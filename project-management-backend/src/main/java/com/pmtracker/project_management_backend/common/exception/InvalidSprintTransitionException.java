package com.pmtracker.project_management_backend.common.exception;

public class InvalidSprintTransitionException extends RuntimeException {

    public InvalidSprintTransitionException() {
        super("This sprint cannot be moved to the requested state");
    }
}
