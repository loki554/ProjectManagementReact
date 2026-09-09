package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Архивация проектов (4.14): {@code POST /api/projects/{id}/archive}, {@code /unarchive} и
 * то, что архив на самом деле означает.
 *
 * <p>Главное здесь — не сами два эндпоинта, а обещание, которое они дают: законченный проект
 * уходит с глаз и больше не меняется. Обещание держится одной проверкой на всё приложение
 * (ProjectAccessService.requireWriteRole), поэтому проверяется оно с разных сторон — задача,
 * комментарий, вики, тэг, спринт, участники, — и вместе с исключениями, без которых архив
 * стал бы состоянием без выхода: разархивировать и удалить архивный проект обязано быть
 * можно. Отдельно — то, что чтение архива остаётся полным: доска, список, поиск.
 */
class ProjectArchiveIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;

    private User owner;
    private User member;
    private Project project;
    private Task task;
    private String ownerHeader;
    private String memberHeader;

    @BeforeEach
    void createProject() {
        owner = saveUser("owner@example.com", "Яковлев", "Олег");
        member = saveUser("member@example.com", "Петров", "Пётр");

        project = saveProject("Archive project", "archive-project");
        join(project, owner, ProjectRole.OWNER);
        join(project, member, ProjectRole.MEMBER);
        task = saveTask("Задача в архивном проекте");

        ownerHeader = "Bearer " + jwtService.generateAccessToken(owner);
        memberHeader = "Bearer " + jwtService.generateAccessToken(member);
    }

    // ----------------------------------------------------------------- само действие

    @Nested
    @DisplayName("архивация")
    class Archiving {

        @Test
        @DisplayName("архивация ставит флаг и дату, возврат снимает оба")
        void archiveAndUnarchive() throws Exception {
            archive(ownerHeader)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.archived").value(true))
                    .andExpect(jsonPath("$.archivedAt").value(notNullValue()));

            mockMvc.perform(post("/api/projects/" + project.getId() + "/unarchive")
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.archived").value(false))
                    .andExpect(jsonPath("$.archivedAt").value(nullValue()));
        }

        /**
         * Повторное нажатие — не ошибка, а просьба, которая уже выполнена (две вкладки,
         * двойной клик). Дата при этом не сдвигается: «когда закончили» не должно зависеть
         * от того, сколько раз нажали кнопку.
         */
        @Test
        @DisplayName("повторная архивация идемпотентна и не сдвигает дату")
        void archiveIsIdempotent() throws Exception {
            archive(ownerHeader).andExpect(status().isOk());
            Instant first = projectRepository.findById(project.getId()).orElseThrow().getArchivedAt();

            archive(ownerHeader).andExpect(status().isOk());

            assertThat(projectRepository.findById(project.getId()).orElseThrow().getArchivedAt()).isEqualTo(first);
        }

        @Test
        @DisplayName("архивирует только владелец")
        void ownerOnly() throws Exception {
            archive(memberHeader)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        }

        @Test
        @DisplayName("архивный проект уходит из списка проектов, но виден в архиве")
        void leavesTheProjectList() throws Exception {
            mockMvc.perform(get("/api/projects").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));

            archive(ownerHeader).andExpect(status().isOk());

            mockMvc.perform(get("/api/projects").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
            mockMvc.perform(get("/api/projects").param("archived", "true").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].slug").value("archive-project"));
        }

        /**
         * «Мои активные задачи» — призыв к действию, а в архивном проекте действовать
         * нечем. Проверка идёт через кросс-проектный список, потому что именно он и есть
         * то место, где архивный проект продолжал бы напоминать о себе каждый день.
         */
        @Test
        @DisplayName("задачи архивного проекта уходят из «моих активных задач»")
        void leavesMyActiveTasks() throws Exception {
            task.setAssignee(member);
            taskRepository.save(task);

            mockMvc.perform(get("/api/tasks/mine").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)));

            archive(ownerHeader).andExpect(status().isOk());

            mockMvc.perform(get("/api/tasks/mine").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.totalItems").value(0));
        }
    }

    // -------------------------------------------------------------- архив только на чтение

    @Nested
    @DisplayName("архивный проект не меняется")
    class ReadOnly {

        @BeforeEach
        void archiveIt() throws Exception {
            archive(ownerHeader).andExpect(status().isOk());
        }

        /**
         * Пять дверей в проект из разных модулей: задачи, комментарии, вики, справочники,
         * спринты. Все они ходят через одну проверку, и смысл теста именно в том, чтобы
         * поймать день, когда появится шестая, мимо неё.
         */
        @Test
        @DisplayName("правки отклоняются со всех сторон: задача, комментарий, вики, тэг, спринт")
        void refusesEveryWrite() throws Exception {
            expectArchived(post("/api/projects/" + project.getId() + "/tasks")
                    .header(AUTHORIZATION, memberHeader)
                    .contentType(APPLICATION_JSON)
                    .content("""
                            {"title":"Ещё одна задача"}"""));

            expectArchived(patch("/api/tasks/" + task.getId())
                    .header(AUTHORIZATION, memberHeader)
                    .contentType(APPLICATION_JSON)
                    .content("""
                            {"title":"Переименовал","status":"NEW","urgency":"MEDIUM","version":0}"""));

            expectArchived(post("/api/tasks/" + task.getId() + "/comments")
                    .header(AUTHORIZATION, memberHeader)
                    .contentType(APPLICATION_JSON)
                    .content("""
                            {"body":"Комментарий в закрытый проект"}"""));

            expectArchived(put("/api/projects/" + project.getId() + "/wiki")
                    .header(AUTHORIZATION, memberHeader)
                    .contentType(APPLICATION_JSON)
                    .content("""
                            {"content":"Дописал в вики","version":0}"""));

            expectArchived(post("/api/projects/" + project.getId() + "/tags")
                    .header(AUTHORIZATION, ownerHeader)
                    .contentType(APPLICATION_JSON)
                    .content("""
                            {"name":"новый","color":"#112233"}"""));

            expectArchived(post("/api/projects/" + project.getId() + "/sprints")
                    .header(AUTHORIZATION, ownerHeader)
                    .contentType(APPLICATION_JSON)
                    .content("""
                            {"name":"Спринт 1","startDate":"2026-09-07","endDate":"2026-09-18"}"""));

            expectArchived(patch("/api/projects/" + project.getId())
                    .header(AUTHORIZATION, ownerHeader)
                    .contentType(APPLICATION_JSON)
                    .content("""
                            {"name":"Новое имя","description":null,"version":0}"""));

            expectArchived(post("/api/projects/" + project.getId() + "/members")
                    .header(AUTHORIZATION, ownerHeader)
                    .contentType(APPLICATION_JSON)
                    .content("""
                            {"email":"newcomer@example.com","role":"MEMBER"}"""));
        }

        @Test
        @DisplayName("читается архивный проект целиком: карточка, доска, список, задача")
        void readsStayOpen() throws Exception {
            mockMvc.perform(get("/api/projects/slug/archive-project").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.archived").value(true));
            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks/board").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)));
            mockMvc.perform(get("/api/tasks/" + task.getId()).header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk());
        }

        /**
         * Поиск архив не прячет — и это ровно то, как в него возвращаются. «Уходит из
         * списка» и «его не найти» — разные вещи, и вторая сделала бы архив свалкой.
         */
        @Test
        @DisplayName("поиск по-прежнему находит задачи архивного проекта")
        void searchStillFindsIt() throws Exception {
            mockMvc.perform(get("/api/search").param("q", "архивном").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)));
        }

        /**
         * Два действия обязаны работать и в архиве, иначе архив — состояние без выхода.
         * Порядок здесь важен: сначала проверяем возврат, потом (уже на действующем
         * проекте, заново заархивированном) — удаление.
         */
        @Test
        @DisplayName("вернуть из архива и удалить архивный проект по-прежнему можно")
        void archiveHasAnExit() throws Exception {
            mockMvc.perform(post("/api/projects/" + project.getId() + "/unarchive")
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.archived").value(false));

            archive(ownerHeader).andExpect(status().isOk());

            mockMvc.perform(delete("/api/projects/" + project.getId()).header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isNoContent());
            assertThat(projectRepository.findById(project.getId())).isEmpty();
        }

        /**
         * Личное в архивном проекте не запрещено: звезда — это закладка того, кто смотрит,
         * а не изменение проекта. Заодно проверка, что «запрещено всё» не стало «запрещено
         * вообще всё».
         */
        @Test
        @DisplayName("звезда проекта ставится и в архиве")
        void personalActionsStayOpen() throws Exception {
            mockMvc.perform(put("/api/projects/" + project.getId() + "/star").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.starredByMe").value(true));
        }
    }

    // -------------------------------------------------------------------------- фикстуры

    private ResultActions archive(String authHeader) throws Exception {
        return mockMvc.perform(post("/api/projects/" + project.getId() + "/archive").header(AUTHORIZATION, authHeader));
    }

    /** Любая правка архивного проекта отвечает одинаково: 409 PROJECT_ARCHIVED. */
    private void expectArchived(RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PROJECT_ARCHIVED"));
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

    private Task saveTask(String title) {
        Task newTask = new Task();
        newTask.setProject(project);
        newTask.setTitle(title);
        newTask.setStatus(TaskStatus.NEW);
        newTask.setUrgency(TaskUrgency.MEDIUM);
        newTask.setPosition(0);
        newTask.setCreatedBy(owner);
        newTask.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        return taskRepository.save(newTask);
    }
}
