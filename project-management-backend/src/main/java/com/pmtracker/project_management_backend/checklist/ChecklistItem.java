package com.pmtracker.project_management_backend.checklist;

import com.pmtracker.project_management_backend.task.Task;
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

import java.time.Instant;
import java.util.UUID;

/**
 * Пункт чек-листа задачи (4.13, V33).
 *
 * <p>Это не подзадача. У подзадачи есть номер, исполнитель, статус, срок и карточка на
 * доске — она существует, чтобы её кому-то поручить. Пункт чек-листа существует только
 * внутри одной задачи и отвечает ровно на один вопрос: сделан этот шаг или ещё нет.
 * Поэтому у него нет ни версии для оптимистичной блокировки (галочка — не форма, которую
 * держат открытой полчаса), ни мягкого удаления (снятый пункт — это опечатка, а не потеря
 * данных), ни автора: кто и когда его отметил, отвечает лента активности проекта, а не
 * строка, у которой галочку ещё и снимают обратно.
 */
@Entity
@Table(name = "task_checklist_items")
public class ChecklistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Задача, которой принадлежит пункт. @ManyToOne на Task, у которого стоит
     * @SQLRestriction("deleted_at is null") (3.5): для пункта это ровно то, что нужно —
     * задача в корзине невидима, а с ней невидим и её чек-лист.
     */
    @ManyToOne
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    private Task task;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(nullable = false)
    private boolean done;

    /** Порядок шагов; см. V33 — хранится явно, а не выводится из времени создания. */
    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
