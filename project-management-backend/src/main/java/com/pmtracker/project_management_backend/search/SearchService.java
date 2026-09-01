package com.pmtracker.project_management_backend.search;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.dto.PageResponse;
import com.pmtracker.project_management_backend.search.dto.SearchResultResponse;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Полнотекстовый поиск по задачам, комментариям и вики (4.1).
 *
 * <p>Два входа — по проекту и по всем проектам пользователя — отличаются ровно проверкой
 * доступа: у проектного она обычная (проект существует, пользователь его участник), у
 * глобального её выполняет сам запрос, отбирая строки только из проектов, где
 * пользователь состоит.
 */
@Service
public class SearchService {

    /**
     * Страница выдачи меньше, чем у списка задач: там строка — это строка таблицы, здесь —
     * карточка со сниппетом в несколько строк. Потолок нужен по той же причине, что и в
     * TaskService: ?size=1000000 не должен возвращать ровно то, от чего пагинацию вводили,
     * тем более что каждая строка страницы стоит отдельного вызова ts_headline.
     */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final SearchRepository searchRepository;
    private final ProjectAccessService projectAccessService;

    public SearchService(SearchRepository searchRepository, ProjectAccessService projectAccessService) {
        this.searchRepository = searchRepository;
        this.projectAccessService = projectAccessService;
    }

    /** Поиск по всем проектам, в которых состоит пользователь. */
    @Transactional(readOnly = true)
    public PageResponse<SearchResultResponse> searchEverything(User currentUser, String query,
                                                               SearchResultType type, int page, int size) {
        return search(null, currentUser, query, type, page, size);
    }

    /** Поиск внутри одного проекта. Доступен любому его участнику, включая VIEWER. */
    @Transactional(readOnly = true)
    public PageResponse<SearchResultResponse> searchProject(User currentUser, UUID projectId, String query,
                                                            SearchResultType type, int page, int size) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        return search(projectId, currentUser, query, type, page, size);
    }

    private PageResponse<SearchResultResponse> search(UUID projectId, User currentUser, String query,
                                                      SearchResultType type, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampPageSize(size));

        // Пустая выдача, а не 400. Строка поиска в шапке отправляет запрос по мере набора, и
        // «ещё ничего не введено» или «введён один дефис» — это нормальное промежуточное
        // состояние ввода, а не ошибка клиента, которую стоило бы показывать пользователю.
        String tsQuery = SearchQueryParser.toTsQuery(query);
        if (tsQuery == null) {
            return PageResponse.from(new PageImpl<>(List.of(), pageable, 0));
        }

        // EnumSet — порядок веток UNION совпадает с порядком объявления в SearchResultType,
        // то есть один и тот же запрос всегда собирается в одну и ту же строку SQL, и
        // Postgres переиспользует для неё готовый план.
        Set<SearchResultType> types = type == null
                ? EnumSet.allOf(SearchResultType.class)
                : EnumSet.of(type);

        Page<SearchResultResponse> result =
                searchRepository.search(projectId, currentUser.getId(), tsQuery, types, pageable);
        return PageResponse.from(result);
    }

    private static int clampPageSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
