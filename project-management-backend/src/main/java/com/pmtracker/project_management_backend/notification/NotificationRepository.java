package com.pmtracker.project_management_backend.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /**
     * То же самое для массовой правки (4.6) — одним DELETE вместо двухсот: смена срока или
     * исполнителя разом обесценивает старые напоминания у всего выделения.
     */
    @Modifying
    @Query("delete from Notification n where n.task.id in :taskIds and n.type in ('task_due_soon', 'task_overdue')")
    void deleteDueDateAlertsForTasks(Collection<UUID> taskIds);

    /**
     * Что собрать в вечернюю сводку (4.3, {@code NotificationDigestJob}): уведомления
     * перечисленных получателей, по которым письма ещё не было.
     *
     * <p>Окно {@code cutoff} обязательно. Без него человек, впервые включивший дайджест,
     * получил бы письмо со всей своей историей за три месяца: {@code email_sent_at} остаётся
     * null не только у «ещё не отправленных», но и у тех, что созданы до появления почтовых
     * уведомлений вообще, и у тех, чей тип был выключен.
     *
     * <p>Получатель и автор подтягиваются fetch join: письмо собирается уже вне этой
     * транзакции, и адрес с именем должны быть в руках заранее. Задача не нужна — всё, что
     * попадает в письмо, лежит в payload (см. {@code NotificationService.basePayload}).
     *
     * <p>Порядок — по получателю и времени: джобу остаётся сгруппировать подряд идущие,
     * а внутри письма события идут так же, как в колокольчике, но снизу вверх по времени.
     */
    @Query("""
            select n from Notification n
            join fetch n.recipient
            left join fetch n.actor
            where n.recipient.id in :recipientIds
              and n.emailSentAt is null
              and n.createdAt >= :cutoff
            order by n.recipient.id, n.createdAt
            """)
    List<Notification> findPendingForDigest(Collection<UUID> recipientIds, Instant cutoff);

    /**
     * Чистка давно прочитанных уведомлений (3.9, NotificationCleanupJob).
     *
     * <p>Порог считается от readAt, а не от createdAt: уведомление, созданное сто дней назад
     * и прочитанное вчера, по createdAt удалилось бы прямо из-под глаз пользователя, тогда
     * как интересует нас «прочитано давно», а не «создано давно».
     *
     * <p>Непрочитанные не трогаются вовсе, каким бы старым ни было уведомление: удалить
     * непрочитанное — значит стереть то, чего человек ни разу не видел, и заодно
     * бесшумно уменьшить счётчик на колокольчике.
     */
    @Modifying
    @Query("delete from Notification n where n.readAt is not null and n.readAt < :cutoff")
    int deleteReadBefore(Instant cutoff);
}
