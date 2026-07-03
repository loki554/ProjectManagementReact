package com.pmtracker.project_management_backend.common.exception;

public class InvalidOrExpiredTokenException extends RuntimeException {

    public InvalidOrExpiredTokenException() {
        super("Confirmation link is invalid or has expired");
    }
}
