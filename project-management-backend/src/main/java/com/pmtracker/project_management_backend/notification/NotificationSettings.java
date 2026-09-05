package com.pmtracker.project_management_backend.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Настройки email-уведомлений одного пользователя (4.3, см. V25).
 * <p>
 * Ключ — id пользователя, без суррогатного: настройки не сущность со своей жизнью, а
 * продолжение пользователя, и второй строки на того же человека быть не может.
 * <p>
 * Связь с {@code User} намеренно сделана голым id, а не {@code @OneToOne}: единственный,
 * кто эту строку читает, — рассылка, и ей нужен ответ «слать ли», а не выгруженный рядом
 * пользователь. Обратной ссылки в {@code User} нет по той же причине — иначе настройки
 * подтягивались бы на каждом запросе, который трогает текущего пользователя, то есть на
 * каждом вообще.
 * <p>
 * Отсутствие строки — это «всё по умолчанию» (см. {@link NotificationSettingsService}).
 * Строка появляется, когда человек первый раз что-то поменял или отписался.
 */
@Entity
@Table(name = "notification_settings")
public class NotificationSettings {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Главный выключатель: его гасит ссылка «отписаться» из письма, не трогая флаги типов. */
    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private NotificationDeliveryMode mode = NotificationDeliveryMode.INSTANT;

    @Column(name = "task_assigned", nullable = false)
    private boolean taskAssigned = true;

    @Column(name = "task_comment", nullable = false)
    private boolean taskComment = true;

    @Column(name = "task_due_soon", nullable = false)
    private boolean taskDueSoon = true;

    @Column(name = "task_overdue", nullable = false)
    private boolean taskOverdue = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Включён ли конкретный тип уведомления. Строковый код, а не enum, потому что таким его
     * знает всё остальное приложение ({@code NotificationService.TYPE_*}, {@code payload},
     * фронтенд). Неизвестный тип считается включённым: новый тип уведомления должен доходить
     * до людей сам, а не молча пропадать до тех пор, пока кто-нибудь не вспомнит про эту
     * строчку — забытый {@code case} здесь стоил бы неотправленных писем, а не исключения.
     */
    public boolean allows(String type) {
        return switch (type) {
            case NotificationService.TYPE_TASK_ASSIGNED -> taskAssigned;
            case NotificationService.TYPE_TASK_COMMENT -> taskComment;
            case NotificationService.TYPE_TASK_DUE_SOON -> taskDueSoon;
            case NotificationService.TYPE_TASK_OVERDUE -> taskOverdue;
            default -> true;
        };
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public void setEmailEnabled(boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }

    public NotificationDeliveryMode getMode() {
        return mode;
    }

    public void setMode(NotificationDeliveryMode mode) {
        this.mode = mode;
    }

    public boolean isTaskAssigned() {
        return taskAssigned;
    }

    public void setTaskAssigned(boolean taskAssigned) {
        this.taskAssigned = taskAssigned;
    }

    public boolean isTaskComment() {
        return taskComment;
    }

    public void setTaskComment(boolean taskComment) {
        this.taskComment = taskComment;
    }

    public boolean isTaskDueSoon() {
        return taskDueSoon;
    }

    public void setTaskDueSoon(boolean taskDueSoon) {
        this.taskDueSoon = taskDueSoon;
    }

    public boolean isTaskOverdue() {
        return taskOverdue;
    }

    public void setTaskOverdue(boolean taskOverdue) {
        this.taskOverdue = taskOverdue;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
