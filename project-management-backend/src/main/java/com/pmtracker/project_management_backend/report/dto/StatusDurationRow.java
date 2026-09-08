package com.pmtracker.project_management_backend.report.dto;

import com.pmtracker.project_management_backend.task.TaskStatus;

import java.math.BigDecimal;

/**
 * Сколько задача в среднем проводит в статусе, в часах.
 *
 * <p>{@code sampleCount} — число законченных отрезков, по которым посчитано среднее, и
 * показывать его обязательно: «в FEEDBACK в среднем 40 часов» по двум наблюдениям и по
 * двумстам — это два разных утверждения, и второе из них — единственное, на которое стоит
 * опираться. {@code averageHours} = null при нулевой выборке: среднего нет, и ноль здесь
 * соврал бы, будто задачи пролетают статус мгновенно.
 */
public record StatusDurationRow(
        TaskStatus status,
        BigDecimal averageHours,
        long sampleCount
) {
}
