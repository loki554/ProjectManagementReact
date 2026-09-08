package com.pmtracker.project_management_backend.report.dto;

import com.pmtracker.project_management_backend.sprint.dto.SprintSummary;

import java.time.LocalDate;
import java.util.List;

/**
 * Burndown одного спринта: как убывало незакрытое от начала к концу.
 *
 * <p>{@code scope} — сколько задач в спринте <i>сейчас</i>, и это осознанное упрощение.
 * Честный burndown умеет показывать и изменение объёма (задачу добавили в спринт на третий
 * день — линия должна дёрнуться вверх), но состав спринта на произвольный день трекер не
 * хранит: событие task_sprint_changed пишется, а вот восстанавливать по нему состав задним
 * числом — это отдельная машина, которая ошибётся молча. Поэтому линия строится по
 * сегодняшнему составу, и её стоит читать как «из того, что в спринте, сколько было
 * незакрыто на такой-то день».
 */
public record BurndownResponse(
        SprintSummary sprint,
        LocalDate startDate,
        LocalDate endDate,
        long scope,
        List<BurndownPoint> points
) {
}
