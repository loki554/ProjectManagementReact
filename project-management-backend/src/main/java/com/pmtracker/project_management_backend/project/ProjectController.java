package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.dto.CreateProjectRequest;
import com.pmtracker.project_management_backend.project.dto.ProjectResponse;
import com.pmtracker.project_management_backend.project.dto.UpdateProjectRequest;
import com.pmtracker.project_management_backend.storage.StoredImageMediaType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
    @Operation(summary = "Список проектов текущего пользователя",
            description = "Только проекты, где пользователь состоит в project_members; без пагинации. "
                    + "По умолчанию — действующие; archived=true отдаёт архив (4.14)")
    public ResponseEntity<List<ProjectResponse>> list(@AuthenticationPrincipal User currentUser,
                                                       @RequestParam(defaultValue = "false") boolean archived) {
        return ResponseEntity.ok(projectService.listForUser(currentUser, archived));
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
    @Operation(summary = "Редактировать проект",
            description = "Только OWNER (см. таблицу ролей): name/description. Архивация — отдельное "
                    + "действие /archive, а не поле этой формы (4.14); архивный проект не редактируется "
                    + "вовсе (409 PROJECT_ARCHIVED)")
    public ResponseEntity<ProjectResponse> update(@AuthenticationPrincipal User currentUser,
                                                   @PathVariable UUID id,
                                                   @Valid @RequestBody UpdateProjectRequest request) {
        return ResponseEntity.ok(projectService.update(currentUser, id, request));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Убрать проект в архив",
            description = "Только OWNER. Проект уходит из списка проектов (остаётся в ?archived=true), "
                    + "перестаёт принимать любые правки (409 PROJECT_ARCHIVED) и пропадает из «моих "
                    + "задач» и напоминаний о сроках. Всё содержимое остаётся доступным на чтение, "
                    + "включая поиск. Идемпотентно")
    public ResponseEntity<ProjectResponse> archive(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(projectService.archive(currentUser, id));
    }

    @PostMapping("/{id}/unarchive")
    @Operation(summary = "Вернуть проект из архива",
            description = "Только OWNER. Работает и над архивным проектом — в этом и смысл. Идемпотентно")
    public ResponseEntity<ProjectResponse> unarchive(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(projectService.unarchive(currentUser, id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить проект",
            description = "Только OWNER; каскадно удаляет project_members на уровне БД. Разрешено и для "
                    + "архивного проекта: архив — это «закончено», а не «нельзя тронуть»")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        projectService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/preview-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить превью-картинку проекта",
            description = "Только OWNER; изображение должно быть квадратным (PNG/JPEG/WEBP/GIF, до 5MB)")
    public ResponseEntity<ProjectResponse> uploadPreviewImage(@AuthenticationPrincipal User currentUser,
                                                               @PathVariable UUID id,
                                                               @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(projectService.uploadPreviewImage(currentUser, id, file));
    }

    // Требует авторизации + членства в проекте (в отличие от аватарки пользователя — данные
    // проекта не публичны никому, кроме его участников), не хостится статикой напрямую.
    @GetMapping("/{id}/preview-image")
    @Operation(summary = "Скачать превью-картинку проекта", description = "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<Resource> getPreviewImage(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        Resource resource = projectService.getPreviewImageResource(currentUser, id);
        MediaType contentType = StoredImageMediaType.of(resource);
        return ResponseEntity.ok().contentType(contentType).body(resource);
    }
}
