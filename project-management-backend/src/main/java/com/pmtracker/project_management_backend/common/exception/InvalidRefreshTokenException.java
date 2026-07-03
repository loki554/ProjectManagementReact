package com.pmtracker.project_management_backend.common.exception;

public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Токен обновления недействителен или истёк");
    }
}
