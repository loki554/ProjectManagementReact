package com.pmtracker.project_management_backend.common.exception;

public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Refresh token is invalid or has expired");
    }
}
