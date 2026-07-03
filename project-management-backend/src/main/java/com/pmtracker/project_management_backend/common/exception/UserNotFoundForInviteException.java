package com.pmtracker.project_management_backend.common.exception;

public class UserNotFoundForInviteException extends RuntimeException {

    public UserNotFoundForInviteException(String email) {
        super("No user with email " + email + " is registered");
    }
}
