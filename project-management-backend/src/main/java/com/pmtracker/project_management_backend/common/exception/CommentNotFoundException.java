package com.pmtracker.project_management_backend.common.exception;

public class CommentNotFoundException extends RuntimeException {

    public CommentNotFoundException() {
        super("Comment not found");
    }
}
