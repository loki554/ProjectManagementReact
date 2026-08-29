package com.pmtracker.project_management_backend.wiki.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.wiki.ProjectWiki;

import java.time.Instant;

public record WikiResponse(
        String content,
        UserSummary updatedBy,
        Instant updatedAt,
        // См. TaskResponse.version. Для вики это важнее всего: страница одна на проект и
        // целиком состоит из одного большого текста, поэтому «победил сохранивший
        // последним» здесь означает потерю всей чужой правки, а не одного поля.
        long version
) {
    /**
     * Версия проекта, у которого вики ещё нет. Не 0: у только что созданной строки версия
     * как раз 0, и совпадение этих двух значений оставило бы дыру — двое открыли пустой
     * редактор, первый сохранил (строка появилась с версией 0), второй присылает свой 0,
     * проверка проходит, страница первого исчезает. С -1 второй получает 409.
     */
    public static final long NO_WIKI_VERSION = -1;

    public static WikiResponse from(ProjectWiki wiki) {
        return new WikiResponse(
                wiki.getContent(),
                wiki.getUpdatedBy() != null ? UserSummary.from(wiki.getUpdatedBy()) : null,
                wiki.getUpdatedAt(),
                wiki.getVersion()
        );
    }

    // У проекта ещё не было ни одного сохранения вики — строка в БД не создаётся
    // до первого PUT, но GET при этом отвечает 200 с пустым контентом, а не 404.
    public static WikiResponse empty() {
        return new WikiResponse("", null, null, NO_WIKI_VERSION);
    }
}
