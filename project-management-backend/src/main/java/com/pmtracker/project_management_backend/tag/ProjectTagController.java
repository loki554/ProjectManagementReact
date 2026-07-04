package com.pmtracker.project_management_backend.tag;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.tag.dto.CreateTagRequest;
import com.pmtracker.project_management_backend.tag.dto.TagResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/tags")
@Tag(name = "Tags", description = "Кастомные тэги проекта")
public class ProjectTagController {

    private final TagService tagService;

    public ProjectTagController(TagService tagService) {
        this.tagService = tagService;
    }

    @PostMapping
    @Operation(summary = "Создать тэг проекта", description = "Только OWNER; имя уникально в рамках проекта")
    public ResponseEntity<TagResponse> create(@AuthenticationPrincipal User currentUser,
                                               @PathVariable UUID projectId,
                                               @Valid @RequestBody CreateTagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tagService.create(currentUser, projectId, request));
    }

    @GetMapping
    @Operation(summary = "Список тэгов проекта", description = "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<List<TagResponse>> list(@AuthenticationPrincipal User currentUser,
                                                    @PathVariable UUID projectId) {
        return ResponseEntity.ok(tagService.list(currentUser, projectId));
    }
}
