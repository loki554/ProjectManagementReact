package com.pmtracker.project_management_backend.mail;

import com.pmtracker.project_management_backend.notification.Notification;

import java.time.Instant;
import java.util.Map;

/**
 * Одно уведомление в том виде, в каком его нужно написать в письме (4.3).
 * <p>
 * Готовые значения, а не {@code Notification}: письмо отправляется после коммита и в другом
 * потоке, где ленивые связи JPA уже не подгрузить, — тот же принцип, что у остальных событий
 * в этом пакете. Заодно это и граница: почта знает про уведомление ровно то, что печатает,
 * и не может случайно начать зависеть от чего-то ещё.
 * <p>
 * {@code payload} едет как есть, потому что он и был задуман снапшотом отображаемых строк
 * (см. {@code NotificationService.basePayload}): те же title/taskNumber/projectSlug, что
 * рисует колокольчик, нужны и письму — включая ссылку на задачу.
 */
public record NotificationMailItem(
        String type,
        /* null у системных типов (task_due_soon/task_overdue) — их создаёт планировщик, а не человек. */
        String actorName,
        Map<String, Object> payload,
        Instant createdAt
) {

    public static NotificationMailItem from(Notification notification, String actorName) {
        return new NotificationMailItem(
                notification.getType(), actorName, notification.getPayload(), notification.getCreatedAt());
    }

    public String stringValue(String key) {
        Object value = payload != null ? payload.get(key) : null;
        return value != null ? value.toString() : null;
    }
}
