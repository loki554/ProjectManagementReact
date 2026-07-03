package com.pmtracker.project_management_backend.common.exception;

public class AssigneeNotProjectMemberException extends RuntimeException {

    public AssigneeNotProjectMemberException() {
        super("Assignee must be a member of the project");
    }
}
