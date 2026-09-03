package com.pmtracker.project_management_backend.project.dto;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.ProjectInvitation;
import com.pmtracker.project_management_backend.project.ProjectRole;

import java.time.Instant;

/**
 * Что показывает страница приглашения тому, кто перешёл по ссылке. Эндпоинт публичный —
 * иначе приглашённому пришлось бы решать, стоит ли заводить аккаунт, глядя на голый токен
 * в адресной строке.
 * <p>
 * Отсюда и состав полей: ровно то, что уже написано в письме, которое человек держит перед
 * глазами, — название проекта, роль, адрес, на который приглашение выписано, и кто позвал.
 * Ни id проекта, ни описания, ни состава участников: до принятия приглашения проект для
 * предъявителя токена — чужой, и знать о нём больше, чем было в письме, ему неоткуда.
 * <p>
 * Чего здесь сознательно нет — признака «на этот адрес уже есть аккаунт». Он сделал бы
 * страницу удобнее (одна кнопка вместо двух), но превратил бы публичный эндпоинт в
 * проверялку существования аккаунтов, а от неё в остальном API аккуратно избавлялись
 * (см. /register и /forgot-password). Цена отказа — человек с уже существующим аккаунтом
 * может пойти по ветке «зарегистрироваться» и получить письмо «аккаунт уже существует»
 * вместо мгновенной подсказки.
 */
public record InvitationPreviewResponse(
        String projectName,
        String email,
        ProjectRole role,
        String invitedByName,
        Instant expiresAt
) {
    public static InvitationPreviewResponse from(ProjectInvitation invitation) {
        User inviter = invitation.getInvitedBy();
        return new InvitationPreviewResponse(
                invitation.getProject().getName(),
                invitation.getEmail(),
                invitation.getRole(),
                inviter == null ? null : inviter.getLastName() + " " + inviter.getFirstName(),
                invitation.getExpiresAt()
        );
    }
}
