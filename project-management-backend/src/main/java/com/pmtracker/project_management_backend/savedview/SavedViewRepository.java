package com.pmtracker.project_management_backend.savedview;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface SavedViewRepository extends JpaRepository<SavedView, UUID> {

    /**
     * Все представления одного человека в одном проекте, в порядке создания: кнопки в панели
     * стоят там же, где их оставили, и не переставляются от переименования соседней.
     *
     * <p>join fetch по той же причине, что и в списке задач: все три ссылки @ManyToOne, то
     * есть EAGER, и без фетча Hibernate догружает каждую отдельным SELECT'ом на строку —
     * десяток представлений превращается в три десятка запросов. Наружу из них уходят одни
     * id (имена тэгов и участников у фронтенда уже загружены — теми же справочниками, из
     * которых он рисует фильтры), но запросы Hibernate сделал бы всё равно.
     */
    @Query("""
            select v from SavedView v
            left join fetch v.assignee
            left join fetch v.tag
            left join fetch v.category
            left join fetch v.sprint
            where v.project.id = :projectId and v.owner.id = :ownerId
            order by v.createdAt asc
            """)
    List<SavedView> findOwnedByProject(UUID projectId, UUID ownerId);

    boolean existsByProjectIdAndOwnerIdAndName(UUID projectId, UUID ownerId, String name);
}
