package com.pmtracker.project_management_backend.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Отчёт по времени за период (4.10).
 *
 * <p>Три разреза одних и тех же часов, и каждый отвечает на свой вопрос: {@code byUser} —
 * «кто сколько отметил» (ради него пункт и заводился), {@code byTask} — «на что ушло»,
 * {@code byDay} — «ровно ли шло». Считать их тремя запросами дешевле, чем отдавать сырые
 * записи и складывать их на клиенте: сумма по группе — работа базы, а не браузера.
 *
 * <p>Период возвращается в ответе, даже если клиент его не присылал: границы по умолчанию
 * выбирает сервер (последние 30 дней), и подпись «за период с … по …» должна брать их
 * оттуда же, а не вычислять заново и разойтись на день.
 */
public record TimeReportResponse(
        LocalDate from,
        LocalDate to,
        /** Фильтр по участнику, как его понял сервер; null — часы всей команды. */
        UUID userId,
        BigDecimal totalHours,
        List<TimeReportUserRow> byUser,
        List<TimeReportTaskRow> byTask,
        List<TimeReportDayRow> byDay
) {
}
