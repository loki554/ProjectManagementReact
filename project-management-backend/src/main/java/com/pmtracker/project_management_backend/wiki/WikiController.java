package com.pmtracker.project_management_backend.wiki;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.wiki.dto.UpdateWikiRequest;
import com.pmtracker.project_management_backend.wiki.dto.WikiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/wiki")
@Tag(name = "Project wiki", description = "Единственная markdown-страница вики проекта")
public class WikiController {

    private final WikiService wikiService;

    public WikiController(WikiService wikiService) {
        this.wikiService = wikiService;
    }

    @GetMapping
    @Operation(summary = "Получить вики проекта", description = "Доступно любому участнику проекта, включая VIEWER; до первого сохранения возвращает пустой content")
    public ResponseEntity<WikiResponse> get(@AuthenticationPrincipal User currentUser,
                                            @PathVariable UUID projectId) {
        return ResponseEntity.ok(wikiService.get(currentUser, projectId));
    }

    @PutMapping
    @Operation(summary = "Сохранить вики проекта", description = "MEMBER и выше; страница создаётся при первом сохранении")
    public ResponseEntity<WikiResponse> update(@AuthenticationPrincipal User currentUser,
                                               @PathVariable UUID projectId,
                                               @Valid @RequestBody UpdateWikiRequest request) {
        return ResponseEntity.ok(wikiService.update(currentUser, projectId, request));
    }
}
