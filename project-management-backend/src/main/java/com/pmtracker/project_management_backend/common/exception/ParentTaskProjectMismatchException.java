package com.pmtracker.project_management_backend.common.exception;

public class ParentTaskProjectMismatchException extends RuntimeException {

    public ParentTaskProjectMismatchException() {
        super("Parent task belongs to a different project");
    }
}
