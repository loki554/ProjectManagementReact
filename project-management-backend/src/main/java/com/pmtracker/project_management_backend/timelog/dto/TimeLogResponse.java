package com.pmtracker.project_management_backend.timelog.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.timelog.TimeLog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TimeLogResponse(
        UUID id,
        UUID taskId,
        UserSummary user,
        BigDecimal hours,
        LocalDate spentOn,
        String description,
        Instant createdAt
) {
    public static TimeLogResponse from(TimeLog timeLog) {
        return new TimeLogResponse(
                timeLog.getId(),
                timeLog.getTask().getId(),
                UserSummary.from(timeLog.getUser()),
                timeLog.getHours(),
                timeLog.getSpentOn(),
                timeLog.getDescription(),
                timeLog.getCreatedAt()
        );
    }
}
