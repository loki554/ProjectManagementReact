package com.pmtracker.project_management_backend.project.dto;

/**
 * Результат POST /api/projects/{id}/members. У приглашения теперь два исхода, и вызывающему
 * надо их различать: зарегистрированный пользователь становится участником сразу
 * ({@code MEMBER_ADDED}, в списке появляется строка), незарегистрированный получает письмо
 * со ссылкой и попадает в проект только когда ею воспользуется ({@code INVITATION_SENT}).
 * <p>
 * Одно поле из двух всегда null. Альтернативой был бы отдельный эндпоинт под каждый исход,
 * но выбирать между ними пришлось бы клиенту — то есть клиент должен был бы заранее знать,
 * зарегистрирован ли адресат, а это ровно то знание, которого у него нет и быть не должно.
 */
public record InviteResponse(
        Status status,
        MemberResponse member,
        InvitationResponse invitation
) {
    public enum Status {
        MEMBER_ADDED,
        INVITATION_SENT
    }

    public static InviteResponse memberAdded(MemberResponse member) {
        return new InviteResponse(Status.MEMBER_ADDED, member, null);
    }

    public static InviteResponse invitationSent(InvitationResponse invitation) {
        return new InviteResponse(Status.INVITATION_SENT, null, invitation);
    }
}
