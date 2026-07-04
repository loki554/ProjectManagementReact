package com.pmtracker.project_management_backend.timelog.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateTimeLogRequest(
        @NotNull @DecimalMin(value = "0.01") @DecimalMax(value = "24") @Digits(integer = 2, fraction = 2) BigDecimal hours,
        @NotNull @PastOrPresent LocalDate spentOn,
        @Size(max = 1000) String description
) {
}
