package com.pmtracker.project_management_backend.common.exception;

public class DuplicateCategoryNameException extends RuntimeException {

    public DuplicateCategoryNameException() {
        super("A category with this name already exists in the project");
    }
}
