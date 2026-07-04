package com.pmtracker.project_management_backend.timelog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface TimeLogRepository extends JpaRepository<TimeLog, UUID> {

    List<TimeLog> findByTaskIdOrderBySpentOnDescCreatedAtDesc(UUID taskId);

    @Query("select coalesce(sum(t.hours), 0) from TimeLog t where t.task.id = :taskId")
    BigDecimal sumHoursByTaskId(UUID taskId);
}
