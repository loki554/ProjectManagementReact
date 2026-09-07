package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.task.TaskStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Массовая правка задач (4.6): «этим N задачам — вот такой статус/исполнитель/тэг/срок».
 *
 * <p><b>Почему «снять поле» — отдельный флаг, а не {@code null}.</b> В массовой правке
 * отсутствующее поле означает «не трогать», и выразить этим же {@code null}'ом «очистить»
 * нечем: JSON {@code "assigneeId": null} и вовсе не присланный {@code assigneeId} доезжают
 * до record'а одинаково, а различать их пришлось бы либо {@code JsonNullable}, либо разбором
 * сырого дерева Jackson. Пара «значение + булев флаг очистки» уже используется в фильтрах
 * того же списка ({@code unassigned}/{@code uncategorized} в TaskListQuery) и по той же
 * причине; флаг сильнее значения, как и там.
 *
 * <p>У статуса пары нет: колонка {@code NOT NULL}, «задачи без статуса» не бывает.
 *
 * <p><b>Про версии.</b> Одиночный PATCH задачи требует {@code version} (3.4) — там человек
 * правит текст, который прочитал, и затирать чужую правку нельзя. Здесь новое значение
 * абсолютное и от прежнего не зависит («этим двадцати — DONE»), поэтому версия не
 * запрашивается: требовать её значило бы обязать клиента таскать двадцать версий и ловить
 * 409 из-за задачи, которой кто-то поменял описание. Гонку на самой строке всё равно
 * поймает {@code @Version} Hibernate и вернёт тот же 409 CONCURRENT_MODIFICATION.
 *
 * @param taskIds      что правим; дубликаты схлопываются, всё должно принадлежать проекту
 *                     из URL — иначе 404 на весь запрос, см. TaskService.bulkUpdate
 * @param clearAssignee снять исполнителя; сильнее assigneeId
 * @param clearTag      снять тэг; сильнее tagId
 * @param clearDueDate  снять срок; сильнее dueDate
 */
public record BulkUpdateTasksRequest(
        // Потолок совпадает с MAX_TASK_PAGE_SIZE (TaskService): выделяют задачи на странице
        // списка, а больше одной страницы в выделение не помещается. Он же ограничивает
        // сверху и количество уведомлений с письмами, которые одно нажатие может породить.
        @NotEmpty @Size(max = 200) List<@NotNull UUID> taskIds,
        TaskStatus status,
        UUID assigneeId,
        // Boolean, а не boolean: у record'а Jackson собирает все компоненты через
        // канонический конструктор и подставляет в отсутствующие null, а null в примитив
        // не лезет — не присланный clearAssignee ронял бы весь запрос в 400 "Malformed
        // request body". Компактный конструктор ниже приводит null к false сразу, поэтому
        // дальше по коду поля читаются как обычные булевы, без проверок на null.
        Boolean clearAssignee,
        UUID tagId,
        Boolean clearTag,
        Instant dueDate,
        Boolean clearDueDate
) {

    public BulkUpdateTasksRequest {
        clearAssignee = Boolean.TRUE.equals(clearAssignee);
        clearTag = Boolean.TRUE.equals(clearTag);
        clearDueDate = Boolean.TRUE.equals(clearDueDate);
    }

    /** Запрошена ли смена исполнителя вообще (в том числе на «никого»). */
    public boolean assigneeRequested() {
        return clearAssignee || assigneeId != null;
    }

    public boolean tagRequested() {
        return clearTag || tagId != null;
    }

    public boolean dueDateRequested() {
        return clearDueDate || dueDate != null;
    }

    /**
     * Хоть одно поле к правке. Запрос без единого поля — не «ничего не изменилось», а
     * ошибка клиента: он просит сервер сделать неизвестно что.
     */
    public boolean hasChanges() {
        return status != null || assigneeRequested() || tagRequested() || dueDateRequested();
    }
}
