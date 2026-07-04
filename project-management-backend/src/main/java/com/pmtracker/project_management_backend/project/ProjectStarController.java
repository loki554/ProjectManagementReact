package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.dto.ProjectStarResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/star")
@Tag(name = "Project stars", description = "Личные 'звёзды' проекта (как на GitHub): отметка текущего пользователя + общий счётчик")
public class ProjectStarController {

    private final ProjectStarService projectStarService;

    public ProjectStarController(ProjectStarService projectStarService) {
        this.projectStarService = projectStarService;
    }

    @GetMapping
    @Operation(summary = "Счётчик звёзд и отметка текущего пользователя", description = "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<ProjectStarResponse> get(@AuthenticationPrincipal User currentUser,
                                                   @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectStarService.get(currentUser, projectId));
    }

    @PutMapping
    @Operation(summary = "Поставить звезду", description = "Идемпотентно; доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<ProjectStarResponse> star(@AuthenticationPrincipal User currentUser,
                                                    @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectStarService.star(currentUser, projectId));
    }

    @DeleteMapping
    @Operation(summary = "Снять звезду", description = "Идемпотентно; доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<ProjectStarResponse> unstar(@AuthenticationPrincipal User currentUser,
                                                      @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectStarService.unstar(currentUser, projectId));
    }
}
