package com.pmtracker.project_management_backend.timelog.dto;

import java.math.BigDecimal;
import java.util.List;

public record TimeLogsResponse(
        List<TimeLogResponse> items,
        BigDecimal totalHours
) {
}
