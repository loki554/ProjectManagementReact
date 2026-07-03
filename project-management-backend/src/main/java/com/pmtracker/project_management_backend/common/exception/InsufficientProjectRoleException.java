package com.pmtracker.project_management_backend.common.exception;

public class InsufficientProjectRoleException extends RuntimeException {

    public InsufficientProjectRoleException() {
        super("Insufficient permissions for this action");
    }
}
