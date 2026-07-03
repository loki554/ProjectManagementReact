package com.pmtracker.project_management_backend.common.exception;

public class CannotRemoveLastOwnerException extends RuntimeException {

    public CannotRemoveLastOwnerException() {
        super("В проекте должен остаться хотя бы один участник с ролью OWNER");
    }
}
