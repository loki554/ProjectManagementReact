package com.pmtracker.project_management_backend.search.dto;

import com.pmtracker.project_management_backend.search.SearchResultType;

import java.time.Instant;
import java.util.UUID;

/**
 * Одна строка выдачи поиска (4.1). Форма общая для всех трёх типов: клиент рисует список
 * одинаковых карточек, а не три разных, и различает их по {@code type}.
 *
 * @param id          id самой сущности — задачи, комментария или страницы вики
 * @param taskNumber  номер задачи, к которой относится результат; null у вики
 * @param taskTitle   название той же задачи; null у вики
 * @param snippet     фрагмент текста вокруг совпадения, размеченный
 *                    {@link com.pmtracker.project_management_backend.search.SearchSnippet}
 * @param updatedAt   момент последнего изменения — им же разрывается равный ранг
 */
public record SearchResultResponse(
        SearchResultType type,
        UUID id,
        UUID projectId,
        String projectName,
        String projectSlug,
        Integer taskNumber,
        String taskTitle,
        String snippet,
        Instant updatedAt
) {
}
