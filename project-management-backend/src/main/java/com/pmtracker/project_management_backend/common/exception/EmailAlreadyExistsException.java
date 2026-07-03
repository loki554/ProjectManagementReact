package com.pmtracker.project_management_backend.common.exception;

public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("Пользователь с email " + email + " уже зарегистрирован");
    }
}
