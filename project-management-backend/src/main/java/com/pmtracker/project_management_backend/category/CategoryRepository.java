package com.pmtracker.project_management_backend.category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findByProjectIdOrderByNameAsc(UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    Optional<Category> findByProjectIdAndName(UUID projectId, String name);

    /**
     * Счётчик задач на каждую категорию для страницы управления — одним запросом, чтобы не
     * ловить N+1 на списке. Категории без задач тоже должны попасть в результат (их можно
     * создать заранее), поэтому left join, а не count по tasks.
     */
    @Query("""
            select c.id as categoryId, count(t.id) as taskCount
            from Category c
            left join Task t on t.category = c
            where c.project.id = :projectId
            group by c.id
            """)
    List<CategoryTaskCount> countTasksByProjectId(UUID projectId);

    interface CategoryTaskCount {
        UUID getCategoryId();

        long getTaskCount();
    }
}
