package com.pmtracker.project_management_backend.common.exception;

public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("Email is not verified. Check your inbox or request a new verification email.");
    }
}
