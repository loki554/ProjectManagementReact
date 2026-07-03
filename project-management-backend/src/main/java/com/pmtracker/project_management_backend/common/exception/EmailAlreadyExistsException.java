package com.pmtracker.project_management_backend.common.exception;

public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("A user with email " + email + " is already registered");
    }
}
