package com.pmtracker.project_management_backend.wiki;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

// Единственная markdown-страница вики проекта (см. V12): surrogate id +
// UNIQUE(project_id) в БД — тот же приём, что у ProjectStar/ProjectMember.
@Entity
@Table(name = "project_wiki")
public class ProjectWiki {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(nullable = false, columnDefinition = "text")
    private String content = "";

    // SET NULL в БД: при удалении аккаунта автора страница вики остаётся.
    @ManyToOne
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    /**
     * Оптимистичная блокировка (3.4, V21). Hibernate сам подставляет её в WHERE каждого
     * UPDATE и увеличивает при успехе; клиент присылает обратно ту версию, которую видел,
     * и получает 409 CONCURRENT_MODIFICATION, если за это время сущность успели изменить.
     *
     * <p>Примитив, а не Long, сознательно: Spring Data определяет «новая сущность или нет»
     * по версии, если её тип ссылочный, и тогда save() managed-сущности с version = null
     * поехал бы по ветке persist. С примитивом проверка остаётся по id, как и была.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        updatedAt = Instant.now();
    }

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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public User getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(User updatedBy) {
        this.updatedBy = updatedBy;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
