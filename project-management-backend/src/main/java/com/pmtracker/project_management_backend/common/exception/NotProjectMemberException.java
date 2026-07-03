package com.pmtracker.project_management_backend.common.exception;

public class NotProjectMemberException extends RuntimeException {

    public NotProjectMemberException() {
        super("You are not a member of this project");
    }
}
