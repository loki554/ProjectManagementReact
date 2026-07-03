package com.pmtracker.project_management_backend.common.exception;

public class InvalidOrExpiredTokenException extends RuntimeException {

    public InvalidOrExpiredTokenException() {
        super("Ссылка для подтверждения недействительна или устарела");
    }
}
