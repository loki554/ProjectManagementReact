package com.pmtracker.project_management_backend.common.exception;

public class ProjectArchivedException extends RuntimeException {

    public ProjectArchivedException() {
        super("The project is archived and read-only");
    }
}
