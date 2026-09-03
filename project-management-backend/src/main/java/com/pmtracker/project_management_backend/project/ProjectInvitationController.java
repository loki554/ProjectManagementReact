package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.dto.InvitationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Непринятые приглашения проекта. Само приглашение выписывается на
 * {@code POST /api/projects/{id}/members} — там же, где добавляется участник: приглашающий
 * не знает и не должен знать, зарегистрирован ли адресат, поэтому и эндпоинт у обоих
 * исходов один (см. {@code InviteResponse}).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/invitations")
@Tag(name = "Project invitations", description = "Приглашения по email тех, у кого ещё нет аккаунта (OWNER/ADMIN)")
public class ProjectInvitationController {

    private final ProjectInvitationService projectInvitationService;

    public ProjectInvitationController(ProjectInvitationService projectInvitationService) {
        this.projectInvitationService = projectInvitationService;
    }

    @GetMapping
    @Operation(summary = "Непринятые приглашения проекта",
            description = "OWNER/ADMIN. Просроченные тоже видны — с признаком expired")
    public ResponseEntity<List<InvitationResponse>> list(@AuthenticationPrincipal User currentUser,
                                                          @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectInvitationService.list(currentUser, projectId));
    }

    @DeleteMapping("/{invitationId}")
    @Operation(summary = "Отозвать приглашение",
            description = "OWNER/ADMIN. Ссылка из письма сразу перестаёт работать")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal User currentUser,
                                        @PathVariable UUID projectId,
                                        @PathVariable UUID invitationId) {
        projectInvitationService.revoke(currentUser, projectId, invitationId);
        return ResponseEntity.noContent().build();
    }
}
