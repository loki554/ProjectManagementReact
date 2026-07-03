package com.pmtracker.project_management_backend.common.exception;

public class InvalidTargetPositionException extends RuntimeException {

    public InvalidTargetPositionException() {
        super("Target position is out of range for this column");
    }
}
