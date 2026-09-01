package com.pmtracker.project_management_backend.search;

import com.pmtracker.project_management_backend.search.dto.SearchResultResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Один запрос поиска по трём источникам (4.1).
 *
 * <p>Через JdbcTemplate, а не через JPA: искомого «результата поиска» как сущности не
 * существует — строка выдачи склеена из трёх разных таблиц, у неё нет ни своей таблицы,
 * ни id, а половина запроса (tsvector, ts_rank, ts_headline) в JPQL непредставима вовсе.
 * Тот же выбор, что у SchedulerLock. Читающему запросу от сессии Hibernate ничего не
 * нужно, поэтому обход ORM здесь ничего не стоит.
 *
 * <p>UNION ALL, а не три отдельных запроса: страница выдачи должна быть отранжирована
 * целиком. Три запроса по десять строк каждый пришлось бы сливать и сортировать в Java, и
 * «страница 3» превратилась бы в «третья страница чего-то».
 */
@Repository
class SearchRepository {

    /**
     * Разбор запроса пользователя. Подставляется в каждую ветку, а не выносится в CTE: CTE,
     * на который ссылаются три ветки UNION, Postgres материализует, и tsquery перестаёт
     * быть константой запроса — а GIN-индексу по search_vector он нужен именно как
     * константа. to_tsquery immutable, так что от повторов вычисление не удваивается.
     */
    private static final String TS_QUERY = "to_tsquery('russian', cast(:tsQuery as text))";

    private static final String TASK_BRANCH = """
            select cast('TASK' as text) as kind,
                   t.id as entity_id,
                   t.project_id as project_id,
                   cast(t.task_number as int) as task_number,
                   cast(t.title as text) as task_title,
                   cast(t.description as text) as body,
                   ts_rank(t.search_vector, %1$s) as rank_score,
                   t.updated_at as updated_at
            from tasks t
            where t.deleted_at is null
              and t.search_vector @@ %1$s
              and t.project_id %2$s
            """;

    // Комментарии удалённых задач не ищутся: задача в корзине невидима для всего остального
    // приложения (V22), и находить её через собственный тред значило бы открыть обход
    // мягкого удаления через поиск.
    private static final String COMMENT_BRANCH = """
            select cast('COMMENT' as text) as kind,
                   c.id as entity_id,
                   t.project_id as project_id,
                   cast(t.task_number as int) as task_number,
                   cast(t.title as text) as task_title,
                   cast(c.body as text) as body,
                   ts_rank(c.search_vector, %1$s) as rank_score,
                   c.created_at as updated_at
            from task_comments c
            join tasks t on t.id = c.task_id
            where t.deleted_at is null
              and c.search_vector @@ %1$s
              and t.project_id %2$s
            """;

    private static final String WIKI_BRANCH = """
            select cast('WIKI' as text) as kind,
                   w.id as entity_id,
                   w.project_id as project_id,
                   cast(null as int) as task_number,
                   cast(null as text) as task_title,
                   cast(w.content as text) as body,
                   ts_rank(w.search_vector, %1$s) as rank_score,
                   w.updated_at as updated_at
            from project_wiki w
            where w.search_vector @@ %1$s
              and w.project_id %2$s
            """;

    /**
     * Проекты, к которым у пользователя есть доступ. Тот же список, что requireMembership
     * проверяет поштучно, — глобальный поиск обязан ограничиваться им, иначе он становится
     * способом читать чужие проекты по одному слову за раз.
     */
    private static final String MEMBER_PROJECTS =
            "in (select pm.project_id from project_members pm where pm.user_id = :userId)";

    /**
     * Тай-брейк по entity_id обязателен. Без него строки с равным рангом и равным
     * updated_at не имеют определённого порядка между запросами, и одна и та же задача
     * может оказаться и на первой странице, и на второй — либо не попасть ни на одну (та же
     * причина, что у сортировки списка задач в 3.3).
     */
    private static final String ORDER_BY = "order by rank_score desc, updated_at desc, entity_id";

    private static final RowMapper<SearchResultResponse> ROW_MAPPER = (rs, rowNum) -> new SearchResultResponse(
            SearchResultType.valueOf(rs.getString("kind")),
            rs.getObject("entity_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getString("project_name"),
            rs.getString("project_slug"),
            rs.getObject("task_number", Integer.class),
            rs.getString("task_title"),
            rs.getString("snippet"),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final NamedParameterJdbcTemplate jdbcTemplate;

    SearchRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param projectId проект, которым ограничен поиск; null — по всем проектам пользователя
     * @param userId    текущий пользователь: им ограничивается глобальный поиск
     * @param tsQuery   готовый аргумент to_tsquery (см. SearchQueryParser)
     * @param types     какие источники искать; пустым набор быть не может — его отсекает сервис
     */
    Page<SearchResultResponse> search(UUID projectId, UUID userId, String tsQuery,
                                      Set<SearchResultType> types, Pageable pageable) {
        String union = buildUnion(projectId, types);
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("tsQuery", tsQuery);
        if (projectId != null) {
            parameters.addValue("projectId", projectId);
        } else {
            parameters.addValue("userId", userId);
        }

        // Считаем до выборки, как в TaskRepositoryImpl: ради пустой страницы (запрошена
        // пятая при двух существующих) второй запрос — вместе с ts_headline на каждую
        // строку — делать незачем.
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from (\n" + union + ") hits", parameters, Long.class);
        long totalItems = Objects.requireNonNullElse(total, 0L);
        if (totalItems == 0 || pageable.getOffset() >= totalItems) {
            return new PageImpl<>(List.of(), pageable, totalItems);
        }

        // Название и slug проекта подтягиваются к уже отобранной странице, а не внутри
        // веток: здесь строк два десятка, а под UNION их могут быть тысячи. По той же
        // причине снаружи стоит и ts_headline — он разбирает текст целиком и стоит дорого.
        String sql = """
                select hits.kind as kind,
                       hits.entity_id as entity_id,
                       hits.project_id as project_id,
                       p.name as project_name,
                       p.slug as project_slug,
                       hits.task_number as task_number,
                       hits.task_title as task_title,
                       ts_headline('russian', coalesce(hits.body, ''), %1$s,
                                   cast(:headlineOptions as text)) as snippet,
                       hits.updated_at as updated_at
                from (
                %2$s
                %3$s
                limit :limit offset :offset
                ) hits
                join projects p on p.id = hits.project_id
                %3$s
                """.formatted(TS_QUERY, union, ORDER_BY);

        parameters.addValue("headlineOptions", SearchSnippet.HEADLINE_OPTIONS)
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());

        List<SearchResultResponse> content = jdbcTemplate.query(sql, parameters, ROW_MAPPER);
        return new PageImpl<>(content, pageable, totalItems);
    }

    /**
     * Ветки собираются строкой, а не одним запросом с флагами вида
     * {@code (:includeTasks or ...)}: невыбранный источник должен исчезнуть из плана
     * запроса, а не отфильтровываться после чтения (та же причина, по которой WHERE списка
     * задач собирается строкой в TaskRepositoryImpl).
     */
    private static String buildUnion(UUID projectId, Set<SearchResultType> types) {
        String scope = projectId != null ? "= :projectId" : MEMBER_PROJECTS;
        return types.stream()
                .map(type -> switch (type) {
                    case TASK -> TASK_BRANCH;
                    case COMMENT -> COMMENT_BRANCH;
                    case WIKI -> WIKI_BRANCH;
                })
                .map(branch -> branch.formatted(TS_QUERY, scope))
                .collect(Collectors.joining("union all\n"));
    }
}
