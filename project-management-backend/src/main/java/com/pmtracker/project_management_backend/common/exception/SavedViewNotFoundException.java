package com.pmtracker.project_management_backend.common.exception;

public class SavedViewNotFoundException extends RuntimeException {

    public SavedViewNotFoundException() {
        super("Saved view not found");
    }
}
