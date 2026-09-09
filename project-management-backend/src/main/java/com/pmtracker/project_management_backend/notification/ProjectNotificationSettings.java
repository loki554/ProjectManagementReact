package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Настройка уведомлений одного человека по одному проекту (4.16, см. V35).
 * <p>
 * Строка появляется только тогда, когда человек явно выбрал режим, отличный от
 * {@link ProjectNotificationMode#PARTICIPATING}, — и исчезает, когда он возвращается к нему
 * (см. {@code ProjectNotificationSettingsService}). Отсутствие строки и есть «по
 * умолчанию»: тот же приём, что у {@link NotificationSettings}, и по той же причине —
 * иначе вступление в проект пришлось бы сопровождать вставкой ради значений, которые и так
 * заданы кодом, а у всех уже существующих участников строки всё равно бы не было.
 * <p>
 * Связи здесь настоящие {@code @ManyToOne}, а не голые id (в отличие от
 * {@link NotificationSettings}): читателей у этой таблицы два, и обоим нужна не только
 * настройка. Рассылка по наблюдателям отдаёт сразу {@code User} получателя, а не его id,
 * которым всё равно пришлось бы идти в {@code users}; а FK на проект — единственное, что
 * гарантирует, что настройки удалённого проекта уедут вместе с ним.
 */
@Entity
@Table(name = "project_notification_settings")
public class ProjectNotificationSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private ProjectNotificationMode mode = ProjectNotificationMode.PARTICIPATING;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public ProjectNotificationMode getMode() {
        return mode;
    }

    public void setMode(ProjectNotificationMode mode) {
        this.mode = mode;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
