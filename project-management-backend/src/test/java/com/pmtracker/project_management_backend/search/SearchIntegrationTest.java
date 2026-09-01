package com.pmtracker.project_management_backend.search;

import com.jayway.jsonpath.JsonPath;
import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.comment.TaskComment;
import com.pmtracker.project_management_backend.comment.TaskCommentRepository;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import com.pmtracker.project_management_backend.wiki.ProjectWiki;
import com.pmtracker.project_management_backend.wiki.ProjectWikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Полнотекстовый поиск: {@code GET /api/search} и {@code GET /api/projects/{id}/search} (4.1).
 * <p>
 * Проверять это можно только на живой Postgres, и не по формальной причине «так принято»:
 * от поиска здесь почти ничего не написано на Java. Морфологию даёт конфигурация
 * {@code russian}, векторы считают генерируемые колонки из V23, ранжирование — ts_rank по
 * весам, сниппет — ts_headline. Тест на моках доказал бы только то, что строка SQL
 * склеилась.
 * <p>
 * Отдельная тема — границы видимости. Поиск единственный в приложении читает сразу три
 * таблицы в обход сервисов с их проверками прав, поэтому «чужой проект не находится» и
 * «удалённая задача не находится» проверяются явно: ошибка здесь означает не кривую
 * выдачу, а утечку.
 */
class SearchIntegrationTest extends IntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskCommentRepository taskCommentRepository;
    @Autowired private ProjectWikiRepository projectWikiRepository;

    private User owner;
    private User stranger;
    private Project project;
    private String authHeader;

    @BeforeEach
    void createProject() {
        owner = saveUser("owner@example.com");
        stranger = saveUser("stranger@example.com");

        project = saveProject("Search project", "search-project");
        join(project, owner, ProjectRole.OWNER);

        authHeader = "Bearer " + jwtService.generateAccessToken(owner);
    }

    // ------------------------------------------------------------------ что находится

    @Nested
    @DisplayName("источники")
    class Sources {

        @Test
        @DisplayName("название и описание задачи, тело комментария, страница вики")
        void searchesAllThreeSources() throws Exception {
            task("Починить импорт", null);
            task("Совсем другая задача", "Здесь про импорт написано в описании");
            comment(task("Задача с обсуждением", null), "Кажется, импорт снова отвалился");
            wiki(project, "Раздел про импорт данных");

            assertThat(labels("/api/search", "q", "импорт")).containsExactlyInAnyOrder(
                    "TASK:Починить импорт",
                    "TASK:Совсем другая задача",
                    "COMMENT:Задача с обсуждением",
                    "WIKI:Search project");
        }

        @Test
        @DisplayName("type ограничивает выдачу одним источником")
        void filtersByType() throws Exception {
            task("Починить импорт", null);
            comment(task("Обсуждение", null), "импорт сломан");
            wiki(project, "импорт данных");

            assertThat(labels("/api/search", "q", "импорт", "type", "TASK"))
                    .containsExactly("TASK:Починить импорт");
            assertThat(labels("/api/search", "q", "импорт", "type", "COMMENT"))
                    .containsExactly("COMMENT:Обсуждение");
            assertThat(labels("/api/search", "q", "импорт", "type", "WIKI"))
                    .containsExactly("WIKI:Search project");
        }

        /**
         * Ради этого пункт и делался: {@code ilike '%…%'} из 3.3 знал только точную
         * подстроку, поэтому «задачами» не находилась «задача».
         */
        @Test
        @DisplayName("морфология: слово находится в любой форме, в обе стороны")
        void matchesDifferentWordForms() throws Exception {
            task("Разобраться с миграциями базы", null);

            assertThat(labels("/api/search", "q", "миграция"))
                    .containsExactly("TASK:Разобраться с миграциями базы");
            assertThat(labels("/api/search", "q", "миграций"))
                    .containsExactly("TASK:Разобраться с миграциями базы");
        }

        /**
         * Конфигурация одна на оба языка: в snowball-конфигурации russian латиница идёт
         * через english_stem (см. комментарий к V23).
         */
        @Test
        @DisplayName("английские слова тоже стеммятся")
        void matchesEnglishWordForms() throws Exception {
            task("Fix failing deployments", null);

            assertThat(labels("/api/search", "q", "deployment"))
                    .containsExactly("TASK:Fix failing deployments");
        }

        @Test
        @DisplayName("последнее слово запроса ищется как префикс — поиск работает по мере набора")
        void matchesThePrefixOfTheLastWord() throws Exception {
            task("Починить импорт", null);

            assertThat(labels("/api/search", "q", "имп")).containsExactly("TASK:Починить импорт");
            // Префикс — только у последнего слова: иначе «за» в середине запроса совпадало
            // бы с половиной базы.
            assertThat(labels("/api/search", "q", "имп починить")).isEmpty();
        }

        @Test
        @DisplayName("несколько слов — это И, а не ИЛИ")
        void requiresAllWords() throws Exception {
            task("Починить импорт", null);
            task("Починить экспорт", null);

            assertThat(labels("/api/search", "q", "починить импорт"))
                    .containsExactly("TASK:Починить импорт");
        }
    }

    // ------------------------------------------------------------------------ выдача

    @Nested
    @DisplayName("выдача")
    class Results {

        /**
         * Порядок задают веса из V23: название задачи — A, описание — B, комментарий и
         * вики — C. Проверяется списком целиком, а не «первым элементом»: перепутанные веса
         * дают правильный первый элемент и неправильные остальные.
         */
        @Test
        @DisplayName("совпадение в названии выше, чем в описании, а оно — выше комментария")
        void ranksTitlesAboveDescriptionsAboveComments() throws Exception {
            comment(task("Тред", null), "тут про биллинг");
            task("Обычная задача", "в описании про биллинг");
            task("Биллинг", null);

            assertThat(labels("/api/search", "q", "биллинг")).containsExactly(
                    "TASK:Биллинг",
                    "TASK:Обычная задача",
                    "COMMENT:Тред");
        }

        @Test
        @DisplayName("сниппет размечает найденные слова управляющими символами")
        void marksMatchesInTheSnippet() throws Exception {
            task("Задача", "Длинное описание, в середине которого встречается импорт данных");

            Map<String, Object> item = items("/api/search", "q", "импорт").get(0);
            assertThat((String) item.get("snippet"))
                    .contains(SearchSnippet.START_MARKER + "импорт" + SearchSnippet.END_MARKER);
        }

        @Test
        @DisplayName("результат по комментарию ведёт на задачу, результат по вики — на проект")
        void carriesEnoughToBuildALink() throws Exception {
            Task task = task("Задача с тредом", null);
            comment(task, "про импорт");
            wiki(project, "про импорт");

            Map<String, Object> found = items("/api/search", "q", "импорт", "type", "COMMENT").get(0);
            assertThat(found.get("taskNumber")).isEqualTo(task.getTaskNumber());
            assertThat(found.get("taskTitle")).isEqualTo("Задача с тредом");
            assertThat(found.get("projectSlug")).isEqualTo("search-project");

            Map<String, Object> wikiHit = items("/api/search", "q", "импорт", "type", "WIKI").get(0);
            assertThat(wikiHit.get("taskNumber")).isNull();
            assertThat(wikiHit.get("taskTitle")).isNull();
            assertThat(wikiHit.get("projectSlug")).isEqualTo("search-project");
        }

        @Test
        @DisplayName("страницы не пересекаются и знают общее количество")
        void pagesTheResults() throws Exception {
            for (int i = 1; i <= 5; i++) {
                task("Импорт " + i, null);
            }

            mockMvc.perform(search("/api/search", "q", "импорт", "page", "0", "size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(5))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.items.length()").value(2));

            List<String> everything = new ArrayList<>();
            everything.addAll(labels("/api/search", "q", "импорт", "page", "0", "size", "2"));
            everything.addAll(labels("/api/search", "q", "импорт", "page", "1", "size", "2"));
            everything.addAll(labels("/api/search", "q", "импорт", "page", "2", "size", "2"));
            assertThat(everything).doesNotHaveDuplicates().hasSize(5);
        }

        @Test
        @DisplayName("страница за концом выдачи — пусто, но totalItems прежний")
        void pastTheEndIsEmptyButStillCounts() throws Exception {
            task("Импорт", null);

            mockMvc.perform(search("/api/search", "q", "импорт", "page", "5", "size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(1))
                    .andExpect(jsonPath("$.items.length()").value(0));
        }

        @Test
        @DisplayName("size за границами диапазона приводится к разрешённому")
        void clampsThePageSize() throws Exception {
            mockMvc.perform(search("/api/search", "q", "импорт", "size", "0"))
                    .andExpect(jsonPath("$.pageSize").value(20));
            mockMvc.perform(search("/api/search", "q", "импорт", "size", "100000"))
                    .andExpect(jsonPath("$.pageSize").value(50));
        }
    }

    // -------------------------------------------------------------------- видимость

    @Nested
    @DisplayName("границы видимости")
    class Visibility {

        @Test
        @DisplayName("глобальный поиск не видит проектов, в которых пользователь не состоит")
        void doesNotLeakForeignProjects() throws Exception {
            Project foreign = saveProject("Чужой", "foreign");
            join(foreign, stranger, ProjectRole.OWNER);
            Task foreignTask = task(foreign, "Секретный импорт", null);
            comment(foreignTask, "и в комментарии импорт");
            wiki(foreign, "и в вики импорт");

            task("Свой импорт", null);

            assertThat(labels("/api/search", "q", "импорт")).containsExactly("TASK:Свой импорт");
        }

        @Test
        @DisplayName("поиск по проекту ограничен этим проектом")
        void scopesToOneProject() throws Exception {
            Project second = saveProject("Второй", "second");
            join(second, owner, ProjectRole.OWNER);
            task(second, "Импорт во втором", null);
            task("Импорт в первом", null);

            assertThat(labels("/api/search", "q", "импорт")).hasSize(2);
            assertThat(labels("/api/projects/" + project.getId() + "/search", "q", "импорт"))
                    .containsExactly("TASK:Импорт в первом");
        }

        @Test
        @DisplayName("поиск по чужому проекту — 403")
        void rejectsNonMembers() throws Exception {
            Project foreign = saveProject("Чужой", "foreign");
            join(foreign, stranger, ProjectRole.OWNER);

            mockMvc.perform(search("/api/projects/" + foreign.getId() + "/search", "q", "импорт"))
                    .andExpect(status().isForbidden());
        }

        /**
         * Задача в корзине невидима для всего остального приложения (V22). Найти её —
         * значит обойти мягкое удаление; найти её комментарий — то же самое, только через
         * соседнюю таблицу, у которой своего deleted_at нет.
         */
        @Test
        @DisplayName("удалённая задача и её комментарии не находятся")
        void ignoresTrashedTasks() throws Exception {
            Task trashed = task("Удалённый импорт", null);
            comment(trashed, "комментарий про импорт");
            // Тем же UPDATE мимо ORM, что и TaskRepository.softDelete: сущность Task для
            // Hibernate уже невидима (@SQLRestriction), а звать сам репозиторий отсюда
            // нечем — @Modifying-запрос требует транзакции, которой у теста нет.
            jdbcTemplate.update("update tasks set deleted_at = now() where id = ?", trashed.getId());

            assertThat(labels("/api/search", "q", "импорт")).isEmpty();
        }
    }

    // ------------------------------------------------------------------------- ввод

    @Nested
    @DisplayName("разбор запроса")
    class QueryParsing {

        @Test
        @DisplayName("пустой и отсутствующий q — пустая страница, а не ошибка")
        void treatsAnEmptyQueryAsAnEmptyPage() throws Exception {
            task("Импорт", null);

            mockMvc.perform(search("/api/search"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(0));
            mockMvc.perform(search("/api/search", "q", "   "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(0));
        }

        /**
         * Синтаксис tsquery в пользовательском вводе — не запрос к Postgres, а мусор:
         * человек набирает слова. Пропущенный внутрь, он стал бы исключением из БД, то есть
         * 500 на строку поиска.
         */
        @Test
        @DisplayName("операторы tsquery во вводе не ломают запрос")
        void survivesTsqueryOperators() throws Exception {
            task("Импорт", null);

            mockMvc.perform(search("/api/search", "q", "&|!():*<>'\\"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(0));
            mockMvc.perform(search("/api/search", "q", "импорт & !"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(1));
        }

        @Test
        @DisplayName("ввод из одних стоп-слов ничего не находит и не падает")
        void survivesStopWordsOnly() throws Exception {
            task("Импорт и экспорт", null);

            mockMvc.perform(search("/api/search", "q", "и не"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(0));
        }
    }

    // --------------------------------------------------------------------- фикстуры

    /** Параметры парой «имя, значение» — так в тесте не приходится ничего URL-кодировать. */
    private MockHttpServletRequestBuilder search(String path, String... params) {
        MockHttpServletRequestBuilder request = get(path).header(AUTHORIZATION, authHeader);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return request;
    }

    private List<Map<String, Object>> items(String path, String... params) throws Exception {
        MvcResult result = mockMvc.perform(search(path, params)).andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.items");
    }

    /**
     * Строка выдачи в виде «тип:на чём нашлось» — этого хватает и на состав, и на порядок,
     * а разбирать в тестах карточку целиком незачем. У вики задачи нет, поэтому её место
     * занимает название проекта.
     */
    private List<String> labels(String path, String... params) throws Exception {
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> item : items(path, params)) {
            Object taskTitle = item.get("taskTitle");
            labels.add(item.get("type") + ":" + (taskTitle == null ? item.get("projectName") : taskTitle));
        }
        return labels;
    }

    private User saveUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        user.setLastName("Тестов");
        user.setFirstName("Тест");
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private Project saveProject(String name, String slug) {
        Project created = new Project();
        created.setName(name);
        created.setSlug(slug);
        created.setCreatedBy(owner);
        return projectRepository.save(created);
    }

    private void join(Project target, User user, ProjectRole role) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(target);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }

    private Task task(String title, String description) {
        return task(project, title, description);
    }

    private Task task(Project target, String title, String description) {
        Task task = new Task();
        task.setProject(target);
        task.setTitle(title);
        task.setDescription(description);
        task.setStatus(TaskStatus.NEW);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setCreatedBy(owner);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(target.getId()));
        return taskRepository.save(task);
    }

    private void comment(Task task, String body) {
        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setAuthor(owner);
        comment.setBody(body);
        taskCommentRepository.save(comment);
    }

    private void wiki(Project target, String content) {
        ProjectWiki page = new ProjectWiki();
        page.setProject(target);
        page.setContent(content);
        page.setUpdatedBy(owner);
        projectWikiRepository.save(page);
    }
}
