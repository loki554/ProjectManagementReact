package com.pmtracker.project_management_backend.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Точка burndown за один день спринта.
 *
 * <p>{@code remaining} = null для дней, которые ещё не наступили. Это не то же самое, что
 * ноль: ноль означает «всё закрыто», а null — «данных пока нет», и рисовать их одинаково
 * значило бы, что спринт, который начался вчера, выглядит уже выполненным. Идеальная линия
 * при этом идёт до конца окна — она и есть план, а план известен заранее.
 */
public record BurndownPoint(
        LocalDate date,
        Long remaining,
        BigDecimal ideal
) {
}
