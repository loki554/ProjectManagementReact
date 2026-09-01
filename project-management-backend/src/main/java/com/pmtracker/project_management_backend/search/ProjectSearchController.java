package com.pmtracker.project_management_backend.search;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.search.dto.SearchResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Отдельный контроллер под /api/projects/{projectId}/search — тот же раскол по базовому
// пути, что у TagController/ProjectTagController и TaskController/ProjectTaskController.
@RestController
@RequestMapping("/api/projects/{projectId}/search")
@Tag(name = "Search", description = "Полнотекстовый поиск по задачам, комментариям и вики")
public class ProjectSearchController {

    private final SearchService searchService;

    public ProjectSearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @Operation(summary = "Поиск внутри проекта",
            description = "То же, что глобальный /api/search, но с выдачей, ограниченной одним проектом. "
                    + "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<PageResponse<SearchResultResponse>> search(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) SearchResultType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "0") int size) {
        return ResponseEntity.ok(searchService.searchProject(currentUser, projectId, q, type, page, size));
    }
}
