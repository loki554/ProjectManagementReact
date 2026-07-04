package com.pmtracker.project_management_backend.common.exception;

public class TagProjectMismatchException extends RuntimeException {

    public TagProjectMismatchException() {
        super("Tag belongs to a different project");
    }
}
