package com.pmtracker.project_management_backend.common.exception;

public class DuplicateTagNameException extends RuntimeException {

    public DuplicateTagNameException() {
        super("A tag with this name already exists in the project");
    }
}
