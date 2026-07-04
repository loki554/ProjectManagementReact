package com.pmtracker.project_management_backend.common.exception;

public class TagNotFoundException extends RuntimeException {

    public TagNotFoundException() {
        super("Tag not found");
    }
}
