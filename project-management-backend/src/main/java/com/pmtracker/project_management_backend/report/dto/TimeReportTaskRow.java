package com.pmtracker.project_management_backend.report.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Часы по одной задаче за период. Номер и заголовок — снимок на момент запроса, а не
 * ссылка: строка отчёта должна читаться сама по себе, как строка списка задач.
 */
public record TimeReportTaskRow(
        UUID taskId,
        int taskNumber,
        String title,
        BigDecimal hours
) {
}
