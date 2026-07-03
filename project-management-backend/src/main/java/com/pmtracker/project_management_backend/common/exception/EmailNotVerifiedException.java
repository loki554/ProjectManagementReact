package com.pmtracker.project_management_backend.common.exception;

public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("Email не подтверждён. Проверьте почту или запросите письмо повторно.");
    }
}
