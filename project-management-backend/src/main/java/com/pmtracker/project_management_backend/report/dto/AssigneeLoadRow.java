package com.pmtracker.project_management_backend.report.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;

/**
 * Загрузка одного исполнителя. {@code user} = null — строка «не назначено», и она здесь
 * такая же полноправная, как остальные: задачи, которые ни на ком не висят, — первое, что
 * стоит увидеть на дашборде.
 */
public record AssigneeLoadRow(
        UserSummary user,
        long openCount,
        long totalCount
) {
}
