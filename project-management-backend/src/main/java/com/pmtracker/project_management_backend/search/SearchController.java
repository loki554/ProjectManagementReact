package com.pmtracker.project_management_backend.search;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.search.dto.SearchResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@Tag(name = "Search", description = "Полнотекстовый поиск по задачам, комментариям и вики")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @Operation(summary = "Глобальный поиск",
            description = "Ищет по всем проектам, в которых состоит текущий пользователь. q — обычные слова "
                    + "(синтаксис tsquery не поддерживается и вырезается), у последнего слова подразумевается "
                    + "префикс; type — ограничить одним источником (TASK/COMMENT/WIKI), по умолчанию все три. "
                    + "page с нуля, size по умолчанию 20 и не больше 50. Пустой или бессмысленный q — пустая "
                    + "страница, а не ошибка. snippet размечен U+0001/U+0002 вокруг найденных слов")
    public ResponseEntity<PageResponse<SearchResultResponse>> search(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) SearchResultType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "0") int size) {
        return ResponseEntity.ok(searchService.searchEverything(currentUser, q, type, page, size));
    }
}
