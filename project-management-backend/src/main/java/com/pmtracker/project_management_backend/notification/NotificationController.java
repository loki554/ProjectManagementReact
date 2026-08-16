package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.notification.dto.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
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

    @PostMapping("/read-all")
    @Operation(summary = "Отметить все уведомления прочитанными")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal User currentUser) {
        notificationService.markAllRead(currentUser);
        return ResponseEntity.noContent().build();
    }
}
