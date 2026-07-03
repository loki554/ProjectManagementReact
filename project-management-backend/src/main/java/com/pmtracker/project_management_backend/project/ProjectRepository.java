package com.pmtracker.project_management_backend.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /**
     * Bulk JPQL-delete вместо {@code delete(entity)}: удаление сущности через persistence
     * context падает с Hibernate TransientPropertyValueException, если в той же сессии уже
     * загружен managed ProjectMember, ссылающийся на этот Project (см. requireMembership в
     * ProjectService) — Hibernate не знает про ON DELETE CASCADE на уровне БД для
     * project_members и путается в порядке flush-действий. Bulk-delete идёт в обход
     * persistence context и графа ассоциаций, каскад на project_members отрабатывает в БД.
     */
    @Modifying
    @Query("delete from Project p where p.id = :id")
    void deleteById(UUID id);
}
