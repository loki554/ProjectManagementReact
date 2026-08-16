package com.pmtracker.project_management_backend.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(UUID recipientId);

    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    boolean existsByRecipientIdAndTaskIdAndType(UUID recipientId, UUID taskId, String type);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipient.id = :recipientId and n.readAt is null")
    void markAllRead(UUID recipientId, Instant now);

    // Дедлайн задачи сдвинулся/задача закрылась — старые "скоро истекает"/"просрочена"
    // больше не актуальны (см. TaskService). Обеих типов сразу, без разбивки по получателю:
    // задача больше не в том состоянии для всех, кто мог получить эти уведомления.
    @Modifying
    @Query("delete from Notification n where n.task.id = :taskId and n.type in ('task_due_soon', 'task_overdue')")
    void deleteDueDateAlerts(UUID taskId);
}
