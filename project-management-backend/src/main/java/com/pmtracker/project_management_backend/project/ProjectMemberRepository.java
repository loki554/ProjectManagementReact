package com.pmtracker.project_management_backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    List<ProjectMember> findByUserIdOrderByProject_CreatedAtDesc(UUID userId);

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);
}
