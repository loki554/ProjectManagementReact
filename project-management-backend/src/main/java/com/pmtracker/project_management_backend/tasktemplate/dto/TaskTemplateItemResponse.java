package com.pmtracker.project_management_backend.tasktemplate.dto;

import com.pmtracker.project_management_backend.tasktemplate.TaskTemplateItem;

import java.util.UUID;

public record TaskTemplateItemResponse(
        UUID id,
        String content,
        int position
) {
    public static TaskTemplateItemResponse from(TaskTemplateItem item) {
        return new TaskTemplateItemResponse(item.getId(), item.getContent(), item.getPosition());
    }
}
