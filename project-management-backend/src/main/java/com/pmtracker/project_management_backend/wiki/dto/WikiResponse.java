package com.pmtracker.project_management_backend.wiki.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.wiki.ProjectWiki;

import java.time.Instant;

public record WikiResponse(
        String content,
        UserSummary updatedBy,
        Instant updatedAt
) {
    public static WikiResponse from(ProjectWiki wiki) {
        return new WikiResponse(
                wiki.getContent(),
                wiki.getUpdatedBy() != null ? UserSummary.from(wiki.getUpdatedBy()) : null,
                wiki.getUpdatedAt()
        );
    }

    // У проекта ещё не было ни одного сохранения вики — строка в БД не создаётся
    // до первого PUT, но GET при этом отвечает 200 с пустым контентом, а не 404.
    public static WikiResponse empty() {
        return new WikiResponse("", null, null);
    }
}
