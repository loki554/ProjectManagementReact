package com.pmtracker.project_management_backend.tasktemplate;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.category.Category;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Шаблон задачи (4.13, V33) — заготовка формы для повторяющегося типа работ.
 *
 * <p>Все поля будущей задачи необязательны, включая заголовок: шаблон, который приносит с
 * собой только чек-лист из десяти пунктов, — законный и, скорее всего, самый частый шаблон.
 * null означает «шаблон про это поле ничего не говорит»: форма оставит его как есть.
 *
 * <p>Исполнителя и срока у шаблона нет намеренно. Срок повторяющейся работы каждый раз свой
 * («к следующей пятнице»), и дата, вмороженная в заготовку, начала бы приезжать в задачи уже
 * просроченной. Исполнитель — тем более: заготовка, назначающая работу конкретному человеку,
 * переживёт его уход из проекта и продолжит выдавать ему задачи.
 */
@Entity
@Table(name = "task_templates")
public class TaskTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    /** Имя шаблона в списке выбора — не заголовок будущей задачи, он ниже. */
    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 255)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskUrgency urgency;

    @ManyToOne
    @JoinColumn(name = "tag_id")
    private Tag tag;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

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

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TaskUrgency getUrgency() {
        return urgency;
    }

    public void setUrgency(TaskUrgency urgency) {
        this.urgency = urgency;
    }

    public Tag getTag() {
        return tag;
    }

    public void setTag(Tag tag) {
        this.tag = tag;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
