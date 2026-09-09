package com.pmtracker.project_management_backend.tasktemplate;

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
 * Пункт чек-листа в шаблоне (4.13, V33). Устроен как ChecklistItem, но без галочки: шаблон —
 * заготовка, отмечают её копию.
 */
@Entity
@Table(name = "task_template_items")
public class TaskTemplateItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "template_id", nullable = false, updatable = false)
    private TaskTemplate template;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public TaskTemplate getTemplate() {
        return template;
    }

    public void setTemplate(TaskTemplate template) {
        this.template = template;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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
}
