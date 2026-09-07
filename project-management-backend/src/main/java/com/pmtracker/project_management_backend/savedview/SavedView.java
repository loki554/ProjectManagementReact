package com.pmtracker.project_management_backend.savedview;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.category.Category;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.task.TaskDueFilter;
import com.pmtracker.project_management_backend.task.TaskSortKey;
import com.pmtracker.project_management_backend.task.TaskStatus;
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
 * Сохранённое представление списка задач (4.7, см. V29): именованный набор фильтров и
 * сортировки, принадлежащий одному человеку в одном проекте.
 *
 * <p>Поля повторяют параметры {@code GET /api/projects/{id}/tasks} один в один — включая
 * пары «значение + булев флаг»: {@code unassigned} рядом с {@code assignee}, {@code
 * uncategorized} рядом с {@code category}. «Без исполнителя» это выбранный пункт фильтра, а
 * не отсутствие выбора, и пустым id его не выразить (тот же приём, что в массовой правке).
 *
 * <p>Ссылки на тэг, категорию и исполнителя — настоящие внешние ключи с ON DELETE SET NULL:
 * удалённый тэг превращает фильтр в «любой тэг», а не в мёртвый id, по которому список молча
 * пустеет.
 */
@Entity
@Table(name = "saved_views")
public class SavedView {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @ManyToOne
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 200)
    private String search;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskStatus status;

    @ManyToOne
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Column(nullable = false)
    private boolean unassigned;

    @Column(name = "assigned_to_me", nullable = false)
    private boolean assignedToMe;

    @ManyToOne
    @JoinColumn(name = "tag_id")
    private Tag tag;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false)
    private boolean uncategorized;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskDueFilter due;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskSortKey sort;

    @Column(nullable = false)
    private boolean descending;

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

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSearch() {
        return search;
    }

    public void setSearch(String search) {
        this.search = search;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public User getAssignee() {
        return assignee;
    }

    public void setAssignee(User assignee) {
        this.assignee = assignee;
    }

    public boolean isUnassigned() {
        return unassigned;
    }

    public void setUnassigned(boolean unassigned) {
        this.unassigned = unassigned;
    }

    public boolean isAssignedToMe() {
        return assignedToMe;
    }

    public void setAssignedToMe(boolean assignedToMe) {
        this.assignedToMe = assignedToMe;
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

    public boolean isUncategorized() {
        return uncategorized;
    }

    public void setUncategorized(boolean uncategorized) {
        this.uncategorized = uncategorized;
    }

    public TaskDueFilter getDue() {
        return due;
    }

    public void setDue(TaskDueFilter due) {
        this.due = due;
    }

    public TaskSortKey getSort() {
        return sort;
    }

    public void setSort(TaskSortKey sort) {
        this.sort = sort;
    }

    public boolean isDescending() {
        return descending;
    }

    public void setDescending(boolean descending) {
        this.descending = descending;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
