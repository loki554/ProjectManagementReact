package com.pmtracker.project_management_backend.common.exception;

public class TimeLogNotFoundException extends RuntimeException {

    public TimeLogNotFoundException() {
        super("Time log not found");
    }
}
