package com.pmtracker.project_management_backend.tag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    List<Tag> findByProjectIdOrderByNameAsc(UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);
}
