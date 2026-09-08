package com.pmtracker.project_management_backend.export.dto;

import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Задача в выгрузке (4.12).
 *
 * <p><b>Почему не TaskResponse.</b> Ответ API — это форма, которая нужна экрану, и меняется
 * она вместе с экраном: {@code position}, {@code version} и {@code openBlockerCount} — это
 * механика перетаскивания карточек, оптимистичной блокировки и предупреждения при переводе
 * в DONE, а не данные задачи. Файл, который человек кладёт в архив и открывает через год,
 * не должен менять набор колонок каждый раз, когда на доске переехала кнопка. Поэтому у
 * выгрузки своя форма, и меняется она только тогда, когда меняются сами данные.
 *
 * <p><b>Тэг, категория и спринт — именами, а не объектами.</b> В файле человека
 * идентифицирует email, а справочник — его название; id тэга вне трекера не открывает
 * ничего и в CSV всё равно схлопнулся бы в имя. Одна форма на оба формата стоит дешевле
 * второй, отличающейся глубиной вложенности.
 *
 * @param parentTaskNumber номер родителя; null — задача верхнего уровня. Дерево в выгрузке
 *                         несёт эта колонка, а не порядок строк (см. ExportService)
 * @param hoursSpent       сумма списанного времени; 0, если не списывали
 */
public record TaskExportRow(
        UUID id,
        int taskNumber,
        Integer parentTaskNumber,
        String title,
        String description,
        TaskStatus status,
        TaskUrgency urgency,
        ExportUser assignee,
        ExportUser createdBy,
        String category,
        String tag,
        String sprint,
        Instant dueDate,
        BigDecimal hoursSpent,
        Instant createdAt,
        Instant updatedAt
) {

    public static TaskExportRow from(Task task, BigDecimal hoursSpent) {
        return new TaskExportRow(
                task.getId(),
                task.getTaskNumber(),
                task.getParentTask() != null ? task.getParentTask().getTaskNumber() : null,
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getUrgency(),
                ExportUser.from(task.getAssignee()),
                ExportUser.from(task.getCreatedBy()),
                task.getCategory() != null ? task.getCategory().getName() : null,
                task.getTag() != null ? task.getTag().getName() : null,
                task.getSprint() != null ? task.getSprint().getName() : null,
                task.getDueDate(),
                hoursSpent,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
