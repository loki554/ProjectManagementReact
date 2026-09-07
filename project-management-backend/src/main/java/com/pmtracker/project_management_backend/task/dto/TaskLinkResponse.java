package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskStatus;

import java.util.UUID;

/**
 * Ссылка на задачу в списке зависимостей (4.8): ровно то, что нужно нарисовать строку и
 * увести по ней на саму задачу.
 *
 * <p>Не {@link TaskResponse}: полный ответ тянет исполнителя, тэг, категорию, автора и сумму
 * часов — на панель зависимостей всего этого не выводится ничего, а два десятка связей
 * превратили бы карточку задачи в мегабайт JSON. {@code taskNumber} здесь важнее id: по нему
 * строится читаемая ссылка, им же задачу называют люди; {@code status} нужен, чтобы отличить
 * закрытый блокер (он уже никому не мешает) от незакрытого.
 */
public record TaskLinkResponse(
        UUID id,
        int taskNumber,
        String title,
        TaskStatus status
) {
    public static TaskLinkResponse from(Task task) {
        return new TaskLinkResponse(task.getId(), task.getTaskNumber(), task.getTitle(), task.getStatus());
    }
}
