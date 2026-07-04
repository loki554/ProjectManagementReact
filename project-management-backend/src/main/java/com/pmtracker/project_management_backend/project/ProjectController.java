package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.dto.CreateProjectRequest;
import com.pmtracker.project_management_backend.project.dto.ProjectResponse;
import com.pmtracker.project_management_backend.project.dto.UpdateProjectRequest;
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
@RequestMapping("/api/projects")
@Tag(name = "Projects", description = "CRUD проектов; видимость и права зависят от роли текущего пользователя в project_members")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @Operation(summary = "Создать проект", description = "Создатель автоматически становится участником с ролью OWNER")
    public ResponseEntity<ProjectResponse> create(@AuthenticationPrincipal User currentUser,
                                                   @Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(currentUser, request));
    }

    @GetMapping
    @Operation(summary = "Список проектов текущего пользователя", description = "Только проекты, где пользователь состоит в project_members; без пагинации")
    public ResponseEntity<List<ProjectResponse>> list(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(projectService.listForUser(currentUser));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Детали проекта", description = "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<ProjectResponse> get(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(projectService.getById(currentUser, id));
    }

    @GetMapping("/slug/{slug}")
    @Operation(summary = "Детали проекта по человекочитаемому slug",
            description = "Используется фронтендом для читаемых URL (/projects/{slug}/...). "
                    + "Для обратной совместимости со старыми ссылками также принимает сырой UUID проекта.")
    public ResponseEntity<ProjectResponse> getBySlug(@AuthenticationPrincipal User currentUser, @PathVariable String slug) {
        return ResponseEntity.ok(projectService.getBySlugOrId(currentUser, slug));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Редактировать проект", description = "Только OWNER (см. таблицу ролей): name/description/archived")
    public ResponseEntity<ProjectResponse> update(@AuthenticationPrincipal User currentUser,
                                                   @PathVariable UUID id,
                                                   @Valid @RequestBody UpdateProjectRequest request) {
        return ResponseEntity.ok(projectService.update(currentUser, id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить проект", description = "Только OWNER; каскадно удаляет project_members на уровне БД")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        projectService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
