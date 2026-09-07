package com.pmtracker.project_management_backend.common.exception;

public class InvalidSprintDatesException extends RuntimeException {

    public InvalidSprintDatesException() {
        super("Sprint end date cannot be earlier than its start date");
    }
}
