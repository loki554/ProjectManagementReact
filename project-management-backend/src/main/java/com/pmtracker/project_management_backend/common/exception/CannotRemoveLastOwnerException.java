package com.pmtracker.project_management_backend.common.exception;

public class CannotRemoveLastOwnerException extends RuntimeException {

    public CannotRemoveLastOwnerException() {
        super("The project must keep at least one member with the OWNER role");
    }
}
