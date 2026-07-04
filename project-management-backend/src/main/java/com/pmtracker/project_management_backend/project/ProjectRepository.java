package com.pmtracker.project_management_backend.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * Атомарно резервирует следующий порядковый номер задачи (#1, #2, ...) для проекта:
     * UPDATE ... RETURNING в одном statement'е, без отдельного SELECT перед ним — так конкурентные
     * транзакции сериализуются через row-lock на projects, и не могут получить одинаковый номер
     * (в отличие от read-then-write через SELECT MAX, см. TaskRepository.findMaxPositionForStatus).
     * Без @Modifying: Spring Data выполняет запрос через getSingleResult(), что нужно, чтобы
     * получить обратно значение из RETURNING (executeUpdate() из @Modifying вернул бы только
     * количество обновлённых строк).
     */
    @Query(value = "update projects set next_task_number = next_task_number + 1 "
            + "where id = :projectId returning next_task_number - 1", nativeQuery = true)
    int reserveNextTaskNumber(@Param("projectId") UUID projectId);

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
