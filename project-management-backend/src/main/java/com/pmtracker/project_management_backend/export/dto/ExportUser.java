package com.pmtracker.project_management_backend.export.dto;

import com.pmtracker.project_management_backend.auth.User;

import java.util.UUID;

/**
 * Человек в выгрузке (4.12).
 *
 * <p>Своя запись, а не {@code UserSummary}: у последнего есть {@code avatarUrl} и
 * {@code username}, то есть ссылка внутрь трекера и идентификатор для @упоминаний. В файле,
 * который открывают через год и, возможно, уже без трекера, ссылка на аватарку — мёртвая
 * строка, а никнейм не отвечает ни на один вопрос, на который не ответил бы email.
 */
public record ExportUser(UUID id, String email, String lastName, String firstName) {

    public static ExportUser from(User user) {
        return user == null ? null
                : new ExportUser(user.getId(), user.getEmail(), user.getLastName(), user.getFirstName());
    }
}
