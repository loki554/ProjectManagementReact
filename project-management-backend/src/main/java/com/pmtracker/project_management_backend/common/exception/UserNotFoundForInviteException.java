package com.pmtracker.project_management_backend.common.exception;

public class UserNotFoundForInviteException extends RuntimeException {

    public UserNotFoundForInviteException(String email) {
        super("Пользователь с email " + email + " не зарегистрирован");
    }
}
