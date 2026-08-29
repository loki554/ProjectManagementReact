package com.pmtracker.project_management_backend.project.dto;

import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectRole;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String slug,
        String description,
        boolean archived,
        String previewImageUrl,
        ProjectRole myRole,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        // См. TaskResponse.version — то же самое для настроек проекта.
        long version
) {
    public static ProjectResponse from(Project project, ProjectRole myRole) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getSlug(),
                project.getDescription(),
                project.isArchived(),
                buildPreviewImageUrl(project),
                myRole,
                project.getCreatedBy().getId(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                project.getVersion()
        );
    }

    // Тот же приём кэш-бастинга ?v=<имя файла на диске>, что и UserSummary.buildAvatarUrl —
    // путь "/projects/{id}/preview-image" сам по себе не меняется между загрузками новой
    // картинки, без версии useAuthenticatedImage не перезапросил бы её после замены.
    private static String buildPreviewImageUrl(Project project) {
        String path = project.getPreviewImagePath();
        if (path == null) {
            return null;
        }
        String storedFileName = path.substring(path.lastIndexOf('/') + 1);
        return "/projects/" + project.getId() + "/preview-image?v=" + storedFileName;
    }
}
