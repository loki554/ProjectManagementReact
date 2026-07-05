package com.pmtracker.project_management_backend.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProjectActivityRepository extends JpaRepository<ProjectActivity, UUID> {

    Page<ProjectActivity> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable pageable);
}
