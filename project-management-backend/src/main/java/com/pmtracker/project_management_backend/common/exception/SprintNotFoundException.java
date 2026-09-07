package com.pmtracker.project_management_backend.common.exception;

public class SprintNotFoundException extends RuntimeException {

    public SprintNotFoundException() {
        super("Sprint not found");
    }
}
