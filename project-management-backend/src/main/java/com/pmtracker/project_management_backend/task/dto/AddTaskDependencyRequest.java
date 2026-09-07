package com.pmtracker.project_management_backend.task.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * «Эту задачу блокирует вот та» (4.8).
 *
 * <p>Направление у эндпоинта одно — добавляется всегда блокер к задаче из URL. Второе
 * направление («эта блокирует вон ту») выражается тем же запросом с другого конца и
 * отдельного поля не требует: связь в базе одна, и две ручки, пишущие одну строку,
 * означали бы два места, где можно ошибиться со стороной.
 *
 * <p>UUID, а не номер задачи, — как и во всех остальных ссылках API; номер живёт в URL
 * фронтенда и разрешается в id тем же {@code /tasks/by-number/{n}}, ради которого он и
 * заведён.
 */
public record AddTaskDependencyRequest(
        @NotNull UUID blockerTaskId
) {
}
