package com.pmtracker.project_management_backend.report.dto;

import com.pmtracker.project_management_backend.task.TaskStatus;

public record StatusCountRow(
        TaskStatus status,
        long count
) {
}
