package com.pmtracker.project_management_backend.common.exception;

public class ParentTaskNotFoundException extends RuntimeException {

    public ParentTaskNotFoundException() {
        super("Parent task not found");
    }
}
