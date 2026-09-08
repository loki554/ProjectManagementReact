package com.pmtracker.project_management_backend.export.dto;

import java.time.Instant;
import java.util.List;

/**
 * JSON-выгрузка задач проекта (4.12).
 *
 * <p><b>Зачем шапка.</b> Голый массив задач был бы короче ровно на три поля и хуже ровно
 * тем, ради чего выгрузку и делают: файл, найденный через год в папке «Загрузки», должен
 * сам говорить, чей он и когда снят. {@code exportedAt} — это ещё и единственный способ
 * отличить две выгрузки одного проекта, сделанные в разные дни, если файл успели
 * переименовать.
 *
 * <p>{@code taskCount} дублирует длину массива сознательно: тот, кто разбирает файл
 * скриптом, по нему проверяет, что файл не обрезан на середине.
 */
public record ProjectTasksExport(
        Instant exportedAt,
        ExportProject project,
        int taskCount,
        List<TaskExportRow> tasks
) {

    public static ProjectTasksExport of(ExportProject project, List<TaskExportRow> tasks) {
        return new ProjectTasksExport(Instant.now(), project, tasks.size(), tasks);
    }
}
