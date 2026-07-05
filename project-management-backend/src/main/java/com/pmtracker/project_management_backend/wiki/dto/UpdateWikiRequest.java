package com.pmtracker.project_management_backend.wiki.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateWikiRequest(
        // NotNull, а не NotBlank: пустая строка — валидный способ очистить вики.
        @NotNull @Size(max = 100000) String content
) {
}
