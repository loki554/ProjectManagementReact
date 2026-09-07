package com.pmtracker.project_management_backend.common.exception;

public class SprintProjectMismatchException extends RuntimeException {

    public SprintProjectMismatchException() {
        super("The sprint belongs to another project");
    }
}
