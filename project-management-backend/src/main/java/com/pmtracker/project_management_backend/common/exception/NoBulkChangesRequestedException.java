package com.pmtracker.project_management_backend.common.exception;

public class NoBulkChangesRequestedException extends RuntimeException {

    public NoBulkChangesRequestedException() {
        super("Bulk update must change at least one field");
    }
}
