package com.pmtracker.project_management_backend.sprint.dto;

import com.pmtracker.project_management_backend.sprint.Sprint;
import com.pmtracker.project_management_backend.sprint.SprintStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Спринт со счётчиком прогресса (4.9).
 *
 * <p>Прогресс — два числа, а не список задач: страница спринтов показывает полосу «сделано
 * из запланированного», а сами задачи приезжают на неё обычным списком задач с фильтром по
 * спринту (тем же, что стоит в таблице и в сохранённых представлениях). Вкладывать задачи
 * в ответ значило бы завести второй способ отдавать задачу — со своей пагинацией, своими
 * фильтрами и своим форматом.
 */
public record SprintResponse(
        UUID id,
        UUID projectId,
        String name,
        String goal,
        LocalDate startDate,
        LocalDate endDate,
        SprintStatus status,
        Instant startedAt,
        Instant completedAt,
        long taskCount,
        // Сколько задач спринта уже закрыто — DONE и REJECTED вместе, см.
        // SprintRepository.countTasksByProjectId о том, почему определение одно на весь пункт.
        long closedTaskCount,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static SprintResponse from(Sprint sprint, long taskCount, long closedTaskCount) {
        return new SprintResponse(
                sprint.getId(),
                sprint.getProject().getId(),
                sprint.getName(),
                sprint.getGoal(),
                sprint.getStartDate(),
                sprint.getEndDate(),
                sprint.getStatus(),
                sprint.getStartedAt(),
                sprint.getCompletedAt(),
                taskCount,
                closedTaskCount,
                sprint.getCreatedBy().getId(),
                sprint.getCreatedAt(),
                sprint.getUpdatedAt()
        );
    }
}
