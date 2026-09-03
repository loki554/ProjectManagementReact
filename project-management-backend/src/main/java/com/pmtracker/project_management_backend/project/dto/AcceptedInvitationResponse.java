package com.pmtracker.project_management_backend.project.dto;

import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectRole;

import java.util.UUID;

/**
 * Ответ на принятие приглашения. Не {@code MemberResponse}, хотя членство именно и создаётся:
 * принявшему нужно не собственное имя с ролью, а то, куда он теперь может пойти, — а id и
 * slug проекта до этого момента ему нигде не показывались (см. {@code InvitationPreviewResponse}
 * о том, почему в превью их нет). Без них страница приглашения могла бы только развести
 * руками и отправить человека искать проект в общем списке.
 */
public record AcceptedInvitationResponse(
        UUID projectId,
        String projectSlug,
        String projectName,
        ProjectRole role
) {
    public static AcceptedInvitationResponse from(Project project, ProjectRole role) {
        return new AcceptedInvitationResponse(project.getId(), project.getSlug(), project.getName(), role);
    }
}
