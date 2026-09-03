package com.pmtracker.project_management_backend.project.dto;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.ProjectInvitation;
import com.pmtracker.project_management_backend.project.ProjectRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Непринятое приглашение — как его видит администратор проекта в списке участников.
 * <p>
 * Токена здесь нет и быть не может: список читают через обычный авторизованный запрос,
 * и попадание туда рабочей ссылки означало бы, что любой ADMIN может войти в проект под
 * приглашённым адресом. Отправить ссылку ещё раз можно только повторным приглашением —
 * оно выпишет новый токен и отправит его в почту, а не в ответ API.
 */
public record InvitationResponse(
        UUID id,
        String email,
        ProjectRole role,
        String invitedByName,
        Instant createdAt,
        Instant expiresAt,
        boolean expired
) {
    public static InvitationResponse from(ProjectInvitation invitation, Instant now) {
        User inviter = invitation.getInvitedBy();
        return new InvitationResponse(
                invitation.getId(),
                invitation.getEmail(),
                invitation.getRole(),
                inviter == null ? null : inviter.getLastName() + " " + inviter.getFirstName(),
                invitation.getCreatedAt(),
                invitation.getExpiresAt(),
                invitation.getExpiresAt().isBefore(now)
        );
    }
}
