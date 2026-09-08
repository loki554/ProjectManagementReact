package com.pmtracker.project_management_backend.report.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;

import java.math.BigDecimal;

/**
 * Часы одного участника за период. {@code entryCount} рядом с суммой не для красоты: сорок
 * часов одной записью и сорок часов двадцатью записями — это разные способы вести учёт, и
 * различать их полезно ровно тому, кто отчёт открыл.
 */
public record TimeReportUserRow(
        UserSummary user,
        BigDecimal hours,
        long entryCount
) {
}
