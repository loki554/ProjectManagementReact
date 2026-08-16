package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.task.Task;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// Уведомление получателя (см. V16). Append-only + одно мутируемое поле (readAt) —
// сеттеров для остальных полей нет намеренно.
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "recipient_id", nullable = false, updatable = false)
    private User recipient;

    // null для системных уведомлений (task_due_soon/task_overdue) и после удаления
    // аккаунта автора (ON DELETE SET NULL).
    @ManyToOne
    @JoinColumn(name = "actor_id", updatable = false)
    private User actor;

    // Строковый код (task_assigned, task_comment, task_due_soon, task_overdue), а не enum —
    // тот же приём, что ProjectActivity.type: новые типы без миграции.
    @Column(nullable = false, length = 32, updatable = false)
    private String type;

    @ManyToOne
    @JoinColumn(name = "task_id", updatable = false)
    private Task task;

    // Снапшот отображаемых строк (title/taskNumber/projectSlug/projectName/commentExcerpt)
    // на момент события — фронтенд не обязан подтягивать задачу отдельным запросом.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public User getRecipient() {
        return recipient;
    }

    public void setRecipient(User recipient) {
        this.recipient = recipient;
    }

    public User getActor() {
        return actor;
    }

    public void setActor(User actor) {
        this.actor = actor;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
