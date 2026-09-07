package com.pmtracker.project_management_backend.common.exception;

public class TaskDependencyCycleException extends RuntimeException {

    public TaskDependencyCycleException() {
        super("This dependency would create a cycle of blockers");
    }
}
