package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.category.dto.CategorySummary;
import com.pmtracker.project_management_backend.tag.dto.TagSummary;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID projectId,
        UUID parentTaskId,
        // Номер родительской задачи (не UUID) — нужен фронтенду для читаемой ссылки
        // "назад к родительской задаче" (/projects/{slug}/tasks/{parentTaskNumber}).
        Integer parentTaskNumber,
        int taskNumber,
        String title,
        String description,
        TaskStatus status,
        UserSummary assignee,
        UserSummary createdBy,
        int position,
        TaskUrgency urgency,
        Instant dueDate,
        TagSummary tag,
        CategorySummary category,
        BigDecimal totalHoursSpent,
        // Сколько незакрытых блокеров у задачи (4.8). Число, а не список: списки нужны на
        // странице задачи и приезжают отдельной ручкой, а карточке на доске и строке в
        // таблице хватает признака «заблокирована» — им же обходится и предупреждение при
        // переводе в DONE. Считается батчем на весь список, см. TaskService.loadOpenBlockerCounts.
        int openBlockerCount,
        Instant createdAt,
        Instant updatedAt,
        // Версия для оптимистичной блокировки (3.4): клиент возвращает её в PATCH и
        // получает 409, если задачу успели изменить, пока форма была открыта.
        long version
) {
    public static TaskResponse from(Task task, BigDecimal totalHoursSpent, int openBlockerCount) {
        return new TaskResponse(
                task.getId(),
                task.getProject().getId(),
                task.getParentTask() != null ? task.getParentTask().getId() : null,
                task.getParentTask() != null ? task.getParentTask().getTaskNumber() : null,
                task.getTaskNumber(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getAssignee() != null ? UserSummary.from(task.getAssignee()) : null,
                UserSummary.from(task.getCreatedBy()),
                task.getPosition(),
                task.getUrgency(),
                task.getDueDate(),
                task.getTag() != null ? TagSummary.from(task.getTag()) : null,
                task.getCategory() != null ? CategorySummary.from(task.getCategory()) : null,
                totalHoursSpent,
                openBlockerCount,
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getVersion()
        );
    }
}
