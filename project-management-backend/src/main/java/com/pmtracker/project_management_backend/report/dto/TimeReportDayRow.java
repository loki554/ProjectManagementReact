package com.pmtracker.project_management_backend.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Часы за один день периода. В ответе присутствуют все дни, включая пустые: полоса «как
 * распределялось внутри периода» без нулевых дней врёт — выходные и отпуск в ней
 * схлопываются, и неделя из двух рабочих дней выглядит как неделя из семи.
 */
public record TimeReportDayRow(
        LocalDate day,
        BigDecimal hours
) {
}
