package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.dto.AcceptedInvitationResponse;
import com.pmtracker.project_management_backend.project.dto.InvitationPreviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Сторона приглашённого: посмотреть, куда зовут, и принять.
 * <p>
 * Отдельный контроллер, а не ветка в {@link ProjectInvitationController}, потому что путь
 * здесь принципиально не содержит id проекта: у того, кто пришёл по ссылке, нет ни доступа
 * к проекту, ни знания о нём — единственное, чем он располагает, это токен.
 */
@RestController
@RequestMapping("/api/invitations")
@Tag(name = "Invitations", description = "Приглашение глазами приглашённого: просмотр и принятие")
public class InvitationController {

    private final ProjectInvitationService projectInvitationService;

    public InvitationController(ProjectInvitationService projectInvitationService) {
        this.projectInvitationService = projectInvitationService;
    }

    /**
     * Публичный (см. SecurityConfig): страницу приглашения открывают до входа, а часто и
     * до того, как аккаунт вообще существует.
     */
    @GetMapping("/{token}")
    @Operation(summary = "Что за приглашение",
            description = "Без аутентификации. Недействительный или просроченный токен — 400 INVALID_TOKEN")
    public ResponseEntity<InvitationPreviewResponse> preview(@PathVariable String token) {
        return ResponseEntity.ok(projectInvitationService.preview(token));
    }

    @PostMapping("/{token}/accept")
    @Operation(summary = "Принять приглашение",
            description = "Требует входа. Адрес аккаунта должен совпадать с адресом приглашения "
                    + "(иначе 403 INVITATION_EMAIL_MISMATCH)")
    public ResponseEntity<AcceptedInvitationResponse> accept(@AuthenticationPrincipal User currentUser,
                                                              @PathVariable String token) {
        return ResponseEntity.ok(projectInvitationService.accept(currentUser, token));
    }
}
