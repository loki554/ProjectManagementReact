package com.pmtracker.project_management_backend.common.exception;

public class SelfTaskDependencyException extends RuntimeException {

    public SelfTaskDependencyException() {
        super("A task cannot block itself");
    }
}
