package com.pmtracker.project_management_backend.savedview;

import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.category.Category;
import com.pmtracker.project_management_backend.category.CategoryRepository;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.tag.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сохранённые представления списка задач (4.7): {@code /api/projects/{id}/views} и
 * {@code /api/views/{id}}.
 * <p>
 * Половина тестов здесь — про то, что представление персонально: его не видно соседу, его
 * нельзя переписать по чужому id, а имя занято только у своего владельца. Вторая половина —
 * про то, что оно переживает жизнь проекта вокруг себя: удалённый тэг превращает фильтр в
 * «любой тэг», а не в мёртвый id, по которому список молча пустеет. Ни то, ни другое не
 * видно из кода: и приоритет флагов, и ON DELETE SET NULL живут в схеме, поэтому проверять
 * это можно только на настоящей Postgres.
 */
class SavedViewIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private SavedViewRepository savedViewRepository;

    private User owner;
    private User viewer;
    private User outsider;
    private Project project;
    private String authHeader;
    private String viewerHeader;
    private String outsiderHeader;

    @BeforeEach
    void createProject() {
        owner = saveUser("owner@example.com", "Яковлев", "Олег");
        viewer = saveUser("viewer@example.com", "Абрамов", "Игорь");
        outsider = saveUser("outsider@example.com", "Чужой", "Человек");

        project = saveProject("Views project", "views-project");
        join(project, owner, ProjectRole.OWNER);
        join(project, viewer, ProjectRole.VIEWER);

        authHeader = "Bearer " + jwtService.generateAccessToken(owner);
        viewerHeader = "Bearer " + jwtService.generateAccessToken(viewer);
        outsiderHeader = "Bearer " + jwtService.generateAccessToken(outsider);
    }

    // ----------------------------------------------------------------------- сохранение

    @Nested
    @DisplayName("сохранение")
    class Creating {

        @Test
        @DisplayName("сохраняется весь набор фильтров и возвращается в том же виде")
        void storesEveryFilter() throws Exception {
            Tag bug = tag("bug");
            Category backend = category("Backend");

            create("""
                    {"name":"Горящее по бэкенду","search":"логин","status":"IN_PROGRESS",
                     "assigneeId":"%s","tagId":"%s","categoryId":"%s","due":"WEEK",
                     "sort":"DUE_DATE","descending":true}""".formatted(owner.getId(), bug.getId(), backend.getId()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Горящее по бэкенду"))
                    .andExpect(jsonPath("$.search").value("логин"))
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.assigneeId").value(owner.getId().toString()))
                    .andExpect(jsonPath("$.tagId").value(bug.getId().toString()))
                    .andExpect(jsonPath("$.categoryId").value(backend.getId().toString()))
                    .andExpect(jsonPath("$.due").value("WEEK"))
                    .andExpect(jsonPath("$.sort").value("DUE_DATE"))
                    .andExpect(jsonPath("$.descending").value(true));
        }

        /**
         * Ради этого флага фильтр «мои» вообще существует отдельно от «исполнитель — такой-то»:
         * представление уезжает по ссылке, и «мои просроченные» у коллеги обязаны показать
         * его задачи, а не задачи автора.
         */
        @Test
        @DisplayName("«мои задачи» сохраняются флагом, а не id автора представления")
        void storesTheViewerFilterAsAFlag() throws Exception {
            create("""
                    {"name":"Мои","assignedToMe":true}""")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.assignedToMe").value(true))
                    .andExpect(jsonPath("$.assigneeId").doesNotExist());
        }

        @Test
        @DisplayName("флаги сильнее значения: «мои» и «без исполнителя» затирают конкретного человека")
        void flagsWinOverValues() throws Exception {
            Category backend = category("Backend");

            create("""
                    {"name":"Ничьи","unassigned":true,"assigneeId":"%s",
                     "uncategorized":true,"categoryId":"%s"}""".formatted(owner.getId(), backend.getId()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.unassigned").value(true))
                    .andExpect(jsonPath("$.assigneeId").doesNotExist())
                    .andExpect(jsonPath("$.uncategorized").value(true))
                    .andExpect(jsonPath("$.categoryId").doesNotExist());
        }

        @Test
        @DisplayName("пустой поиск — это «фильтра нет», а не поиск по пустой строке")
        void treatsBlankSearchAsNoFilter() throws Exception {
            create("""
                    {"name":"Без поиска","search":"   "}""")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.search").doesNotExist());
        }

        @Test
        @DisplayName("сортировка по умолчанию — по номеру задачи")
        void defaultsToTaskNumberSorting() throws Exception {
            create("""
                    {"name":"Просто фильтр","status":"NEW"}""")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sort").value("NUMBER"))
                    .andExpect(jsonPath("$.descending").value(false));
        }

        @Test
        @DisplayName("имя без содержимого — 400")
        void rejectsABlankName() throws Exception {
            create("""
                    {"name":"   ","status":"NEW"}""").andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("своё имя занято, чужое — нет")
        void namesAreUniquePerOwner() throws Exception {
            create("""
                    {"name":"Мои просроченные","assignedToMe":true,"due":"OVERDUE"}""")
                    .andExpect(status().isCreated());

            create("""
                    {"name":"Мои просроченные","due":"TODAY"}""")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("DUPLICATE_SAVED_VIEW_NAME"));

            // У соседа своё такое же — и это нормально: списки не пересекаются.
            mockMvc.perform(post("/api/projects/" + project.getId() + "/views")
                            .header(AUTHORIZATION, viewerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Мои просроченные","assignedToMe":true,"due":"OVERDUE"}"""))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("исполнитель не из проекта — 400")
        void rejectsAnAssigneeFromOutsideTheProject() throws Exception {
            create("""
                    {"name":"Чужой","assigneeId":"%s"}""".formatted(outsider.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ASSIGNEE_NOT_PROJECT_MEMBER"));
        }

        @Test
        @DisplayName("тэг и категория из чужого проекта — отказ, а не заведомо пустое представление")
        void rejectsReferencesFromAnotherProject() throws Exception {
            Project other = saveProject("Other", "other-project");
            Tag foreignTag = tag(other, "foreign");
            Category foreignCategory = category(other, "Foreign");

            create("""
                    {"name":"Чужой тэг","tagId":"%s"}""".formatted(foreignTag.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("TAG_PROJECT_MISMATCH"));

            create("""
                    {"name":"Чужая категория","categoryId":"%s"}""".formatted(foreignCategory.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("CATEGORY_NOT_FOUND"));
        }

        /**
         * Сохранённый фильтр ничего не меняет в проекте и никому, кроме автора, не виден,
         * поэтому роль здесь ни при чём — а VIEWER как раз та роль, которая только и делает,
         * что читает списки.
         */
        @Test
        @DisplayName("VIEWER заводит свои представления наравне с владельцем")
        void viewersMaySaveViewsToo() throws Exception {
            mockMvc.perform(post("/api/projects/" + project.getId() + "/views")
                            .header(AUTHORIZATION, viewerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Читаю только своё","assignedToMe":true}"""))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("посторонний — 403")
        void rejectsOutsiders() throws Exception {
            mockMvc.perform(post("/api/projects/" + project.getId() + "/views")
                            .header(AUTHORIZATION, outsiderHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Чужой проект"}"""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("NOT_A_PROJECT_MEMBER"));
        }
    }

    // --------------------------------------------------------------------------- список

    @Nested
    @DisplayName("список")
    class Listing {

        @Test
        @DisplayName("в списке только свои представления, в порядке создания")
        void listsOwnViewsInCreationOrder() throws Exception {
            create("""
                    {"name":"Первое","status":"NEW"}""").andExpect(status().isCreated());
            create("""
                    {"name":"Второе","due":"WEEK"}""").andExpect(status().isCreated());

            mockMvc.perform(post("/api/projects/" + project.getId() + "/views")
                            .header(AUTHORIZATION, viewerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Соседское"}"""))
                    .andExpect(status().isCreated());

            assertThat(names(authHeader)).containsExactly("Первое", "Второе");
            assertThat(names(viewerHeader)).containsExactly("Соседское");
        }

        @Test
        @DisplayName("посторонний не видит списка вовсе")
        void hidesTheListFromOutsiders() throws Exception {
            mockMvc.perform(get("/api/projects/" + project.getId() + "/views")
                            .header(AUTHORIZATION, outsiderHeader))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------------ изменение

    @Nested
    @DisplayName("изменение и удаление")
    class Updating {

        /**
         * Главное свойство PUT здесь: обновление — это «запомни то, что сейчас на экране».
         * Фильтр, снятый в интерфейсе, обязан исчезнуть и в представлении — с семантикой
         * PATCH («отсутствующее поле не трогать») снять его было бы нечем.
         */
        @Test
        @DisplayName("перезапись снимает фильтры, которых нет в запросе")
        void overwritesInsteadOfMerging() throws Exception {
            UUID id = createAndGetId("""
                    {"name":"Горящее","status":"IN_PROGRESS","due":"WEEK","assignedToMe":true,"descending":true}""");

            update(id, """
                    {"name":"Горящее","due":"OVERDUE"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.due").value("OVERDUE"))
                    .andExpect(jsonPath("$.status").doesNotExist())
                    .andExpect(jsonPath("$.assignedToMe").value(false))
                    .andExpect(jsonPath("$.descending").value(false));
        }

        @Test
        @DisplayName("переименование в занятое своё имя — 409, в собственное прежнее — ок")
        void keepsNamesUnique() throws Exception {
            createAndGetId("""
                    {"name":"Первое"}""");
            UUID second = createAndGetId("""
                    {"name":"Второе"}""");

            update(second, """
                    {"name":"Первое"}""")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("DUPLICATE_SAVED_VIEW_NAME"));

            update(second, """
                    {"name":"Второе","due":"TODAY"}""")
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("удаление убирает представление из списка")
        void deletesTheView() throws Exception {
            UUID id = createAndGetId("""
                    {"name":"Временное","due":"TODAY"}""");

            mockMvc.perform(delete("/api/views/" + id).header(AUTHORIZATION, authHeader))
                    .andExpect(status().isNoContent());

            assertThat(names(authHeader)).isEmpty();
        }

        /**
         * Чужое представление — 404, а не 403: отказ по правам сообщил бы, что представление
         * с таким id существует и принадлежит кому-то ещё. Личный набор фильтров соседа —
         * не то, о существовании чего стоит рассказывать перебором id.
         */
        @Test
        @DisplayName("чужое представление не найдено — ни на правку, ни на удаление")
        void hidesSomeoneElsesView() throws Exception {
            UUID id = createAndGetId("""
                    {"name":"Моё","due":"WEEK"}""");

            mockMvc.perform(put("/api/views/" + id)
                            .header(AUTHORIZATION, viewerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Захвачено"}"""))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("SAVED_VIEW_NOT_FOUND"));

            mockMvc.perform(delete("/api/views/" + id).header(AUTHORIZATION, viewerHeader))
                    .andExpect(status().isNotFound());

            // И оно на месте — отказ был отказом, а не тихим успехом.
            assertThat(names(authHeader)).containsExactly("Моё");
        }

        @Test
        @DisplayName("несуществующее представление — 404")
        void rejectsUnknownIds() throws Exception {
            mockMvc.perform(delete("/api/views/" + UUID.randomUUID()).header(AUTHORIZATION, authHeader))
                    .andExpect(status().isNotFound());
        }
    }

    // ------------------------------------------------------- жизнь проекта вокруг вьюхи

    @Nested
    @DisplayName("ссылки на удаляемое")
    class DanglingReferences {

        /**
         * Ради этого фильтры и хранятся колонками с внешними ключами, а не строкой запроса:
         * удалённый тэг превращает фильтр в «любой тэг» (ON DELETE SET NULL), а не в мёртвый
         * id, по которому представление молча показывает пустой список.
         */
        @Test
        @DisplayName("удаление тэга и категории расширяет представление, а не ломает его")
        void widensTheViewWhenAReferenceDisappears() throws Exception {
            Tag bug = tag("bug");
            Category backend = category("Backend");
            UUID id = createAndGetId("""
                    {"name":"По тэгу","tagId":"%s","categoryId":"%s","due":"WEEK"}"""
                    .formatted(bug.getId(), backend.getId()));

            tagRepository.delete(bug);
            categoryRepository.delete(backend);
            tagRepository.flush();
            categoryRepository.flush();

            mockMvc.perform(get("/api/projects/" + project.getId() + "/views").header(AUTHORIZATION, authHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(id.toString()))
                    .andExpect(jsonPath("$[0].tagId").doesNotExist())
                    .andExpect(jsonPath("$[0].categoryId").doesNotExist())
                    // Остальные фильтры на месте: расширился один, а не сбросилось всё.
                    .andExpect(jsonPath("$[0].due").value("WEEK"));
        }

        @Test
        @DisplayName("удаление проекта уносит представления с собой")
        void cascadesWithTheProject() throws Exception {
            createAndGetId("""
                    {"name":"Пропадёт вместе с проектом"}""");

            projectRepository.delete(project);
            projectRepository.flush();

            assertThat(savedViewRepository.count()).isZero();
        }
    }

    // ------------------------------------------------------------------------ хелперы

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post("/api/projects/" + project.getId() + "/views")
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions update(UUID viewId, String body) throws Exception {
        return mockMvc.perform(put("/api/views/" + viewId)
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    private UUID createAndGetId(String body) throws Exception {
        MvcResult result = create(body).andExpect(status().isCreated()).andReturn();
        Matcher matcher = ID.matcher(result.getResponse().getContentAsString());
        if (!matcher.find()) {
            throw new AssertionError("No id in the response: " + result.getResponse().getContentAsString());
        }
        return UUID.fromString(matcher.group(1));
    }

    private static final Pattern NAME = Pattern.compile("\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private List<String> names(String header) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/" + project.getId() + "/views")
                        .header(AUTHORIZATION, header))
                .andExpect(status().isOk())
                .andReturn();
        Matcher matcher = NAME.matcher(result.getResponse().getContentAsString());
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private User saveUser(String email, String lastName, String firstName) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(usernameFrom(email));
        user.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        user.setLastName(lastName);
        user.setFirstName(firstName);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private Project saveProject(String name, String slug) {
        Project newProject = new Project();
        newProject.setName(name);
        newProject.setSlug(slug);
        newProject.setCreatedBy(owner);
        return projectRepository.save(newProject);
    }

    private void join(Project target, User user, ProjectRole role) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(target);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }

    private Tag tag(Project target, String name) {
        Tag tag = new Tag();
        tag.setProject(target);
        tag.setName(name);
        tag.setColor("#888888");
        tag.setCreatedBy(owner);
        return tagRepository.save(tag);
    }

    private Tag tag(String name) {
        return tag(project, name);
    }

    private Category category(Project target, String name) {
        Category category = new Category();
        category.setProject(target);
        category.setName(name);
        category.setCreatedBy(owner);
        return categoryRepository.save(category);
    }

    private Category category(String name) {
        return category(project, name);
    }
}
