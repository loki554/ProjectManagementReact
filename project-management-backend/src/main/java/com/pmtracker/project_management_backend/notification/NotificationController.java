package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.notification.dto.NotificationResponse;
import com.pmtracker.project_management_backend.notification.dto.UnsubscribeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "Уведомления текущего пользователя (колокольчик в хедере)")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationSettingsService settingsService;

    public NotificationController(NotificationService notificationService,
                                  NotificationSettingsService settingsService) {
        this.notificationService = notificationService;
        this.settingsService = settingsService;
    }

    @GetMapping
    @Operation(summary = "Список уведомлений текущего пользователя", description = "Свежие сверху; page size фиксирован = 20")
    public ResponseEntity<PageResponse<NotificationResponse>> list(@AuthenticationPrincipal User currentUser,
                                                                     @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(notificationService.list(currentUser, page));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Счётчик непрочитанных уведомлений", description = "Опрашивается поллингом для бейджа на колокольчике")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(Map.of("count", notificationService.unreadCount(currentUser)));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Отметить одно уведомление прочитанным", description = "Только собственное уведомление текущего пользователя")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        notificationService.markRead(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Отписка по ссылке из письма (4.3). Публичный эндпоинт — второй в приложении после
     * превью приглашения и по той же причине: человек, дошедший до этой кнопки, скорее всего
     * не в системе, а «войдите, чтобы перестать получать письма» — это отписка, которой нет.
     * Удостоверяет его подписанный токен из письма (см. UnsubscribeTokenService).
     * <p>
     * POST, а не GET по ссылке: по ссылкам из писем ходят почтовые сканеры и превьюшники
     * мессенджеров, и отписка на GET срабатывала бы у них — за человека и без его ведома.
     * Ссылка ведёт на страницу фронтенда, а сюда приходит уже нажатая кнопка.
     */
    @PostMapping("/unsubscribe")
    @Operation(summary = "Отписаться от писем-уведомлений",
            description = "Публично, по токену из письма. Гасит только общий выключатель — набор типов и режим сохраняются")
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody UnsubscribeRequest request) {
        settingsService.unsubscribe(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Отметить все уведомления прочитанными")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal User currentUser) {
        notificationService.markAllRead(currentUser);
        return ResponseEntity.noContent().build();
    }
}
