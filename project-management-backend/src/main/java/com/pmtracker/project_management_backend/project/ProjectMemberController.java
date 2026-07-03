package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.dto.InviteMemberRequest;
import com.pmtracker.project_management_backend.project.dto.MemberResponse;
import com.pmtracker.project_management_backend.project.dto.UpdateMemberRoleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/members")
@Tag(name = "Project members", description = "Управление участниками проекта и их ролями (OWNER/ADMIN/MEMBER/VIEWER)")
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;

    public ProjectMemberController(ProjectMemberService projectMemberService) {
        this.projectMemberService = projectMemberService;
    }

    @GetMapping
    @Operation(summary = "Список участников проекта", description = "Доступно любому участнику, включая VIEWER")
    public ResponseEntity<List<MemberResponse>> list(@AuthenticationPrincipal User currentUser,
                                                       @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectMemberService.list(currentUser, projectId));
    }

    @PostMapping
    @Operation(summary = "Пригласить участника по email", description = "Только для уже зарегистрированных пользователей; OWNER/ADMIN")
    public ResponseEntity<MemberResponse> invite(@AuthenticationPrincipal User currentUser,
                                                  @PathVariable UUID projectId,
                                                  @Valid @RequestBody InviteMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectMemberService.invite(currentUser, projectId, request));
    }

    @PatchMapping("/{userId}")
    @Operation(summary = "Сменить роль участника", description = "OWNER/ADMIN; нельзя оставить проект без ни одного OWNER")
    public ResponseEntity<MemberResponse> updateRole(@AuthenticationPrincipal User currentUser,
                                                      @PathVariable UUID projectId,
                                                      @PathVariable UUID userId,
                                                      @Valid @RequestBody UpdateMemberRoleRequest request) {
        return ResponseEntity.ok(projectMemberService.updateRole(currentUser, projectId, userId, request));
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "Удалить участника из проекта", description = "OWNER/ADMIN; нельзя удалить последнего OWNER")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal User currentUser,
                                        @PathVariable UUID projectId,
                                        @PathVariable UUID userId) {
        projectMemberService.remove(currentUser, projectId, userId);
        return ResponseEntity.noContent().build();
    }
}
