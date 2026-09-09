package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.notification.dto.ProjectNotificationSettingsResponse;
import com.pmtracker.project_management_backend.notification.dto.UpdateProjectNotificationSettingsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * «Следить за проектом / отписаться» (4.16).
 * <p>
 * Путь — под проектом, а не под {@code /api/users/me}, в отличие от глобальных настроек
 * (4.3): это настройка пары «человек и проект», и живёт она рядом со звездой проекта, с
 * которой у неё общая природа — личная отметка участника, не меняющая сам проект.
 * <p>
 * PUT, а не PATCH: поле здесь одно, и «прислали часть» от «прислали всё» не отличается.
 * Он же идемпотентен — повторное нажатие той же кнопки (две вкладки, двойной клик) это не
 * ошибка, а просьба, которая уже выполнена.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/notification-settings")
@Tag(name = "Project notification settings",
        description = "Насколько плотно присылать уведомления по конкретному проекту (4.16)")
public class ProjectNotificationSettingsController {

    private final ProjectNotificationSettingsService settingsService;

    public ProjectNotificationSettingsController(ProjectNotificationSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @Operation(summary = "Режим уведомлений текущего пользователя по проекту",
            description = "Ничего не менявшему отдаётся PARTICIPATING — поведение по умолчанию. "
                    + "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<ProjectNotificationSettingsResponse> get(@AuthenticationPrincipal User currentUser,
                                                                   @PathVariable UUID projectId) {
        return ResponseEntity.ok(settingsService.get(currentUser, projectId));
    }

    @PutMapping
    @Operation(summary = "Выбрать режим уведомлений по проекту",
            description = "ALL — обо всём в проекте, PARTICIPATING — только о своём, MUTED — ничего. "
                    + "Идемпотентно; доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<ProjectNotificationSettingsResponse> update(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectNotificationSettingsRequest request) {
        return ResponseEntity.ok(settingsService.update(currentUser, projectId, request));
    }
}
