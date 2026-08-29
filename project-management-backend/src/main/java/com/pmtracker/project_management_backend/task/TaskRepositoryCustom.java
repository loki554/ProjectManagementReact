package com.pmtracker.project_management_backend.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Ручная часть TaskRepository: постраничный поиск задач проекта (3.3).
 *
 * <p>Отдельный фрагмент, а не @Query, потому что и WHERE, и ORDER BY здесь собираются из
 * входных параметров: восемь необязательных фильтров и десять ключей сортировки в виде
 * готовых аннотаций — это либо сотня методов, либо один запрос с десятком
 * «(:x is null or ...)», который планировщик Postgres не сможет нормально использовать.
 */
public interface TaskRepositoryCustom {

    Page<Task> search(UUID projectId, TaskListQuery query, Pageable pageable);
}
