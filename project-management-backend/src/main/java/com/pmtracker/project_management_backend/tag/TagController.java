package com.pmtracker.project_management_backend.tag;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.tag.dto.TagResponse;
import com.pmtracker.project_management_backend.tag.dto.UpdateTagRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tags/{id}")
@Tag(name = "Tags", description = "Кастомные тэги проекта")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @PatchMapping
    @Operation(summary = "Редактировать тэг", description = "Только OWNER")
    public ResponseEntity<TagResponse> update(@AuthenticationPrincipal User currentUser,
                                               @PathVariable UUID id,
                                               @Valid @RequestBody UpdateTagRequest request) {
        return ResponseEntity.ok(tagService.update(currentUser, id, request));
    }

    @DeleteMapping
    @Operation(summary = "Удалить тэг", description = "Только OWNER; у задач, использовавших тэг, tag становится null")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        tagService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
