package com.pmtracker.project_management_backend.sprint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Тело заведения и правки спринта (4.9). Один DTO на оба: у спринта четыре поля, и правка
 * его карточки — это та же форма, что и заведение, целиком. PATCH с «отсутствующее поле =
 * не трогать» здесь только мешал бы: снятую цель нечем было бы отличить от неприсланной.
 *
 * <p>Статуса среди полей нет намеренно. Он меняется не правкой формы, а отдельными
 * действиями «начать» и «завершить»: у второго есть последствия (недоделанные задачи
 * куда-то переезжают), и прятать их в PATCH карточки означало бы закрывать спринт
 * нечаянно, поправив в нём опечатку.
 *
 * @param goal      зачем собрали спринт; пустая строка и {@code null} — одно и то же «цели нет»
 * @param startDate первый день окна; у майлстоуна совпадает с {@code endDate}
 * @param endDate   последний день окна включительно; не раньше {@code startDate}
 */
public record SprintRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String goal,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate
) {
}
