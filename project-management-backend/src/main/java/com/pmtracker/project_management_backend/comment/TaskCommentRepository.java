package com.pmtracker.project_management_backend.comment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskCommentRepository extends JpaRepository<TaskComment, UUID> {

    List<TaskComment> findByTaskIdOrderByCreatedAtDesc(UUID taskId);

    List<TaskComment> findByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
