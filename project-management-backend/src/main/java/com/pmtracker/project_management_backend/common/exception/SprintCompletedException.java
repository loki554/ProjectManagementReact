package com.pmtracker.project_management_backend.common.exception;

public class SprintCompletedException extends RuntimeException {

    public SprintCompletedException() {
        super("A completed sprint no longer accepts tasks");
    }
}
