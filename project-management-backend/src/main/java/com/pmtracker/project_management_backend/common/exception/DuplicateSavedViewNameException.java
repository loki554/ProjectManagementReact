package com.pmtracker.project_management_backend.common.exception;

public class DuplicateSavedViewNameException extends RuntimeException {

    public DuplicateSavedViewNameException() {
        super("A saved view with this name already exists in the project");
    }
}
