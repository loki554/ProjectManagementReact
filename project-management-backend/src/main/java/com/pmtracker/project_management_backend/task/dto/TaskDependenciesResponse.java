package com.pmtracker.project_management_backend.task.dto;

import java.util.List;

/**
 * Обе стороны зависимостей одной задачи (4.8) одним ответом.
 *
 * <p>Разделять на два эндпоинта незачем: панель на странице задачи всегда показывает оба
 * списка сразу, и два запроса за одним экраном — это просто два запроса. Изменение любой из
 * сторон тоже возвращает целиком этот же объект, чтобы клиенту не приходилось перечитывать
 * то, что сервер уже посчитал.
 *
 * @param blockedBy кто мешает закрыть эту задачу
 * @param blocks    кому мешает она сама
 */
public record TaskDependenciesResponse(
        List<TaskLinkResponse> blockedBy,
        List<TaskLinkResponse> blocks
) {
}
