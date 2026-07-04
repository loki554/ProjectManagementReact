package com.pmtracker.project_management_backend.tag.dto;

import com.pmtracker.project_management_backend.tag.Tag;

import java.util.UUID;

public record TagSummary(
        UUID id,
        String name,
        String color
) {
    public static TagSummary from(Tag tag) {
        return new TagSummary(tag.getId(), tag.getName(), tag.getColor());
    }
}
