package com.pmtracker.project_management_backend.common.exception;

public class AlreadyProjectMemberException extends RuntimeException {

    public AlreadyProjectMemberException() {
        super("Пользователь уже является участником проекта");
    }
}
