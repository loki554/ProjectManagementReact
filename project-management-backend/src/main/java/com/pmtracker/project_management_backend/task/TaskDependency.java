package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Связь «blocker должен закрыться раньше, чем blocked» (4.8, V30).
 *
 * <p>Ребро направленное и хранится один раз: «блокирует» и «заблокирована» — это одна и та
 * же строка, прочитанная с разных концов. Строки неизменяемы — связь либо есть, либо её
 * удалили, менять в ней нечего, поэтому сеттеры только на поля, задаваемые при создании.
 *
 * <p><b>Осторожно с загрузкой сущности целиком.</b> Обе ссылки на {@link Task} обязательны,
 * а сама Task помечена {@code @SQLRestriction("deleted_at is null")}: если задача уехала в
 * корзину (3.5), обращение к {@code getBlocker()} упрётся в невидимую строку. Поэтому и
 * репозиторий, и сервис читают отсюда не сущность целиком, а сразу нужные проекции —
 * задачи через явный join (тогда мягко удалённые честно отваливаются) либо голые id из
 * внешних ключей (тогда @SQLRestriction вообще ни при чём, что и нужно поиску цикла).
 * См. TaskDependencyRepository.
 */
@Entity
@Table(name = "task_dependencies")
public class TaskDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Тот, кто мешает: пока он не закрыт, blocked закрывать рано. */
    @ManyToOne
    @JoinColumn(name = "blocker_task_id", nullable = false, updatable = false)
    private Task blocker;

    /** Тот, кому мешают. */
    @ManyToOne
    @JoinColumn(name = "blocked_task_id", nullable = false, updatable = false)
    private Task blocked;

    // null после удаления аккаунта автора связи (ON DELETE SET NULL) — как actor_id в ленте.
    @ManyToOne
    @JoinColumn(name = "created_by", updatable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Task getBlocker() {
        return blocker;
    }

    public void setBlocker(Task blocker) {
        this.blocker = blocker;
    }

    public Task getBlocked() {
        return blocked;
    }

    public void setBlocked(Task blocked) {
        this.blocked = blocked;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
