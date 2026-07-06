package com.pmtracker.project_management_backend.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProjectActivityRepository extends JpaRepository<ProjectActivity, UUID> {

    Page<ProjectActivity> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable pageable);

    // Лента одной задачи (вкладка «Активность» на странице просмотра). projectId в условии
    // не только ради индекса: membership проверяется по projectId из URL, поэтому чужой
    // taskId просто даст пустой результат, а не утечку событий другого проекта.
    Page<ProjectActivity> findByProjectIdAndTaskIdOrderByCreatedAtDesc(UUID projectId, UUID taskId, Pageable pageable);
}
