package com.pmtracker.project_management_backend.activity;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.Project;
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

// Событие ленты активности (см. V13). Строки append-only: событие никогда не
// редактируется, поэтому сеттеры только для полей, задаваемых при создании.
@Entity
@Table(name = "project_activity")
public class ProjectActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    // null после удаления аккаунта автора (ON DELETE SET NULL).
    @ManyToOne
    @JoinColumn(name = "actor_id", updatable = false)
    private User actor;

    // Строковый код события (task_created, member_added, ...), а не enum: новые типы
    // добавляются без миграции, старые записи не ломают десериализацию.
    @Column(nullable = false, length = 64, updatable = false)
    private String type;

    // null для событий уровня проекта и после удаления задачи (payload хранит снапшот).
    @ManyToOne
    @JoinColumn(name = "task_id", updatable = false)
    private Task task;

    // Отображаемые строки на момент события (имена, названия) + коды статусов/срочности,
    // которые переводит фронтенд.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
