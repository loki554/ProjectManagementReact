package com.pmtracker.project_management_backend.export.dto;

import com.pmtracker.project_management_backend.project.Project;

import java.util.UUID;

/**
 * Проект в шапке JSON-выгрузки (4.12): чей это файл.
 *
 * <p>Три поля, а не карточка проекта целиком: имя отвечает человеку, слаг — тому, кто
 * разбирает выгрузки скриптом и складывает их по папкам, id — тому, кто будет сверять две
 * выгрузки одного проекта, переименованного между ними.
 */
public record ExportProject(UUID id, String slug, String name) {

    public static ExportProject from(Project project) {
        return new ExportProject(project.getId(), project.getSlug(), project.getName());
    }
}
