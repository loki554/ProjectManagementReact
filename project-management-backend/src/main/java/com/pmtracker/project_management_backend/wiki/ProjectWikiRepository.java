package com.pmtracker.project_management_backend.wiki;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectWikiRepository extends JpaRepository<ProjectWiki, UUID> {

    Optional<ProjectWiki> findByProjectId(UUID projectId);
}
