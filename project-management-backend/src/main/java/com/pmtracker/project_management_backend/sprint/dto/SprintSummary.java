package com.pmtracker.project_management_backend.sprint.dto;

import com.pmtracker.project_management_backend.sprint.Sprint;
import com.pmtracker.project_management_backend.sprint.SprintStatus;

import java.util.UUID;

/**
 * Спринт внутри задачи — имя и статус, без дат и счётчиков (парный к TagSummary и
 * CategorySummary). Статус здесь нужен: бейдж «в текущем спринте» и бейдж «в спринте,
 * который ещё не начали» — разные сообщения, а идти за ним вторым запросом на каждую
 * строку списка задач незачем.
 */
public record SprintSummary(
        UUID id,
        String name,
        SprintStatus status
) {
    public static SprintSummary from(Sprint sprint) {
        return new SprintSummary(sprint.getId(), sprint.getName(), sprint.getStatus());
    }
}
