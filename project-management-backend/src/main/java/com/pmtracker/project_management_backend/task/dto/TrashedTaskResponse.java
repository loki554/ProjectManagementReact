package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.task.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка корзины проекта (3.5).
 *
 * <p>Своя DTO, а не TaskResponse: удалённая задача не открывается, не редактируется и не
 * участвует ни в одном списке — про неё нужно знать ровно столько, чтобы узнать её в
 * корзине и решить, восстанавливать ли.
 *
 * @param subtaskCount сколько подзадач вернётся вместе с ней
 * @param purgeAfter   когда её физически удалит TaskCleanupJob; считается на бэкенде, чтобы
 *                     срок хранения был один и не расходился с расписанием чистки
 */
public record TrashedTaskResponse(
        UUID id,
        int taskNumber,
        String title,
        TaskStatus status,
        Instant deletedAt,
        Instant purgeAfter,
        long subtaskCount
) {
    public static TrashedTaskResponse from(TaskRepository.TrashedTask task, Instant purgeAfter) {
        return new TrashedTaskResponse(
                task.getId(),
                task.getTaskNumber(),
                task.getTitle(),
                TaskStatus.valueOf(task.getStatus()),
                task.getDeletedAt(),
                purgeAfter,
                task.getSubtaskCount()
        );
    }
}
