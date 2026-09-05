package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.notification.dto.NotificationSettingsResponse;
import com.pmtracker.project_management_backend.notification.dto.UpdateNotificationSettingsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Настройки уведомлений живут под {@code /api/users/me}, рядом с профилем и паролем, — это
 * настройки человека, а не коллекции уведомлений. Отдельный контроллер, а не метод в
 * {@code UserController}: пакет notification владеет и сущностью, и сервисом, и разносить
 * их с эндпоинтом значило бы завести user-у зависимость на notification ради двух методов.
 */
@RestController
@RequestMapping("/api/users/me/notification-settings")
@Tag(name = "Notification settings", description = "Что и как часто присылать почтой (4.3)")
public class NotificationSettingsController {

    private final NotificationSettingsService settingsService;

    public NotificationSettingsController(NotificationSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @Operation(summary = "Настройки email-уведомлений текущего пользователя",
            description = "Пользователю, ничего не менявшему, отдаются значения по умолчанию: мгновенно и обо всём")
    public ResponseEntity<NotificationSettingsResponse> get(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(settingsService.get(currentUser));
    }

    @PatchMapping
    @Operation(summary = "Изменить настройки email-уведомлений",
            description = "Полная замена: форма присылает все поля сразу")
    public ResponseEntity<NotificationSettingsResponse> update(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody UpdateNotificationSettingsRequest request) {
        return ResponseEntity.ok(settingsService.update(currentUser, request));
    }
}
