package com.pmtracker.project_management_backend.common.exception;

public class NotProjectMemberException extends RuntimeException {

    public NotProjectMemberException() {
        super("Вы не являетесь участником этого проекта");
    }
}
