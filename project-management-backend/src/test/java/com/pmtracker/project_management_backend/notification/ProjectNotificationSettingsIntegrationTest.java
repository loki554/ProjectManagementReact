package com.pmtracker.project_management_backend.notification;

import com.jayway.jsonpath.JsonPath;
import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Настраиваемые уведомления по проектам (4.16): {@code GET/PUT
 * /api/projects/{id}/notification-settings} и то, что выбранный режим на самом деле меняет.
 *
 * <p>Проверять здесь надо не эндпоинт (одно поле, два метода), а два обещания, каждое из
 * которых даётся на словах и ломается молча. «Ничего» обязано означать ничего — ни письма,
 * ни строчки в колокольчике, — причём для всех типов сразу, включая назначение и упоминание,
 * то есть ровно те, ради которых уведомления вообще существуют. «Всё» обязано приносить то,
 * чего без него не было: чужую задачу и чужой комментарий.
 *
 * <p>Отдельная тема — что настройка не переживает членства. Оставленная строка выглядела бы
 * как тихо восстановившаяся при повторном приглашении подписка, и заметить это можно было
 * бы только по письмам о проекте, из которого человека полгода назад исключили.
 */
class ProjectNotificationSettingsIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private User member;
    private User viewer;
    private User outsider;
    private Project project;

    private String ownerAuth;
    private String memberAuth;
    private String viewerAuth;
    private String outsiderAuth;

    @BeforeEach
    void createProject() {
        owner = createUser("owner@example.com", "Владелец");
        member = createUser("member@example.com", "Участник");
        viewer = createUser("viewer@example.com", "Наблюдатель");
        outsider = createUser("outsider@example.com", "Посторонний");

        project = new Project();
        project.setName("Notification modes");
        project.setSlug("notification-modes");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        addMember(owner, ProjectRole.OWNER);
        addMember(member, ProjectRole.MEMBER);
        addMember(viewer, ProjectRole.VIEWER);

        ownerAuth = "Bearer " + jwtService.generateAccessToken(owner);
        memberAuth = "Bearer " + jwtService.generateAccessToken(member);
        viewerAuth = "Bearer " + jwtService.generateAccessToken(viewer);
        outsiderAuth = "Bearer " + jwtService.generateAccessToken(outsider);
    }

    // ------------------------------------------------------------------- сама настройка

    @Nested
    @DisplayName("хранение режима")
    class Storage {

        @Test
        @DisplayName("ничего не менявшему отдаётся PARTICIPATING, и строки в базе нет")
        void defaultsToParticipating() throws Exception {
            getMode(memberAuth).andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("PARTICIPATING"));

            assertThat(settingsRowCount()).isZero();
        }

        @Test
        @DisplayName("выбранный режим сохраняется и читается обратно")
        void storesTheChosenMode() throws Exception {
            setMode(memberAuth, "ALL").andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("ALL"));

            getMode(memberAuth).andExpect(jsonPath("$.mode").value("ALL"));
            assertThat(settingsRowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("возврат к PARTICIPATING удаляет строку, а не пишет её значением по умолчанию")
        void returningToDefaultDropsTheRow() throws Exception {
            setMode(memberAuth, "MUTED").andExpect(status().isOk());
            assertThat(settingsRowCount()).isEqualTo(1);

            setMode(memberAuth, "PARTICIPATING").andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("PARTICIPATING"));

            assertThat(settingsRowCount()).isZero();
        }

        @Test
        @DisplayName("повторный PUT того же режима идемпотентен и не плодит строк")
        void repeatedPutIsIdempotent() throws Exception {
            setMode(memberAuth, "ALL").andExpect(status().isOk());
            setMode(memberAuth, "ALL").andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("ALL"));

            assertThat(settingsRowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("настройки двух людей по одному проекту независимы")
        void settingsArePerPerson() throws Exception {
            setMode(memberAuth, "MUTED").andExpect(status().isOk());

            getMode(ownerAuth).andExpect(jsonPath("$.mode").value("PARTICIPATING"));
        }

        @Test
        @DisplayName("несуществующий режим — 400, а не молча применённый null")
        void rejectsUnknownMode() throws Exception {
            setMode(memberAuth, "SOMETIMES").andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------------------- права

    @Nested
    @DisplayName("права")
    class Access {

        @Test
        @DisplayName("наблюдатель настраивает себе уведомления наравне со всеми")
        void viewerMayChooseTheMode() throws Exception {
            setMode(viewerAuth, "MUTED").andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("MUTED"));
        }

        @Test
        @DisplayName("посторонний — 403 и на чтение, и на запись")
        void outsiderIsRejected() throws Exception {
            getMode(outsiderAuth).andExpect(status().isForbidden());
            setMode(outsiderAuth, "ALL").andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("аноним — 401")
        void anonymousIsRejected() throws Exception {
            mockMvc.perform(get(settingsPath())).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("архив не мешает выключить себе проект: архивный проект не меняется, а письма про него — да")
        void archiveDoesNotBlockTheSetting() throws Exception {
            mockMvc.perform(post("/api/projects/" + project.getId() + "/archive").header(AUTHORIZATION, ownerAuth))
                    .andExpect(status().isOk());

            setMode(memberAuth, "MUTED").andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("MUTED"));
        }
    }

    // ---------------------------------------------------------------------- MUTED

    @Nested
    @DisplayName("режим «ничего»")
    class Muted {

        @Test
        @DisplayName("назначение задачи не доходит ни колокольчиком, ни письмом")
        void assignmentIsSilent() throws Exception {
            setMode(memberAuth, "MUTED").andExpect(status().isOk());
            clearMailbox();

            createTask("Задача выключившему проект", member);

            notifications(memberAuth).andExpect(jsonPath("$.items", hasSize(0)));
            assertNoEmailSent();
        }

        @Test
        @DisplayName("упоминание по имени — тоже ничего: «выключено» без исключений, иначе это не выключено")
        void mentionIsSilent() throws Exception {
            Task task = createTask("Задача владельца", owner);
            setMode(memberAuth, "MUTED").andExpect(status().isOk());
            clearMailbox();

            comment(task, ownerAuth, "@" + member.getUsername() + " посмотрите, пожалуйста");

            notifications(memberAuth).andExpect(jsonPath("$.items", hasSize(0)));
            assertNoEmailSent();
        }

        @Test
        @DisplayName("выключен один проект, а не человек: в соседнем уведомления приходят")
        void otherProjectsKeepWorking() throws Exception {
            Project other = new Project();
            other.setName("Second project");
            other.setSlug("second-project");
            other.setCreatedBy(owner);
            projectRepository.save(other);
            join(other, owner, ProjectRole.OWNER);
            join(other, member, ProjectRole.MEMBER);

            setMode(memberAuth, "MUTED").andExpect(status().isOk());

            mockMvc.perform(post("/api/projects/" + other.getId() + "/tasks")
                            .header(AUTHORIZATION, ownerAuth)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Задача в соседнем проекте","assigneeId":"%s"}"""
                                    .formatted(member.getId())))
                    .andExpect(status().isCreated());

            notifications(memberAuth)
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].type").value(NotificationService.TYPE_TASK_ASSIGNED));
        }
    }

    // ------------------------------------------------------------------------ ALL

    @Nested
    @DisplayName("режим «всё в проекте»")
    class Watching {

        @Test
        @DisplayName("новая чужая задача приносит task_created")
        void newTaskReachesTheWatcher() throws Exception {
            setMode(memberAuth, "ALL").andExpect(status().isOk());

            createTask("Задача, к которой наблюдатель непричастен", null);

            notifications(memberAuth)
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].type").value(NotificationService.TYPE_TASK_CREATED));
        }

        @Test
        @DisplayName("свою же заведённую задачу наблюдатель себе не присылает")
        void ownTaskDoesNotEcho() throws Exception {
            setMode(memberAuth, "ALL").andExpect(status().isOk());

            mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, memberAuth)
                            .contentType(APPLICATION_JSON)
                            .content("{\"title\":\"Моя собственная задача\"}"))
                    .andExpect(status().isCreated());

            notifications(memberAuth).andExpect(jsonPath("$.items", hasSize(0)));
        }

        @Test
        @DisplayName("исполнителю приходит только назначение: два письма про одну задачу в одну секунду — это не «плотнее»")
        void assigneeGetsOnlyTheAssignment() throws Exception {
            setMode(memberAuth, "ALL").andExpect(status().isOk());

            createTask("Задача наблюдателю", member);

            notifications(memberAuth)
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].type").value(NotificationService.TYPE_TASK_ASSIGNED));
        }

        @Test
        @DisplayName("комментарий к чужой задаче приносит task_comment — тот же тип, что и «прокомментировали вашу»")
        void commentsOnForeignTasksReachTheWatcher() throws Exception {
            Task task = createTask("Задача владельца", owner);
            setMode(memberAuth, "ALL").andExpect(status().isOk());

            comment(task, ownerAuth, "Пишу сам себе в свою же задачу");

            notifications(memberAuth)
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].type").value(NotificationService.TYPE_TASK_COMMENT));
        }

        @Test
        @DisplayName("упоминание сильнее слежки: одно уведомление, и это task_mention")
        void mentionWinsOverWatching() throws Exception {
            Task task = createTask("Задача владельца", owner);
            setMode(memberAuth, "ALL").andExpect(status().isOk());

            comment(task, ownerAuth, "@" + member.getUsername() + " взгляните");

            notifications(memberAuth)
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].type").value(NotificationService.TYPE_TASK_MENTION));
        }

        @Test
        @DisplayName("подзадача — такое же появление задачи в проекте")
        void subtasksReachTheWatcherToo() throws Exception {
            Task parent = createTask("Родительская задача", owner);
            setMode(memberAuth, "ALL").andExpect(status().isOk());

            mockMvc.perform(post("/api/tasks/" + parent.getId() + "/subtasks")
                            .header(AUTHORIZATION, ownerAuth)
                            .contentType(APPLICATION_JSON)
                            .content("{\"title\":\"Подзадача\"}"))
                    .andExpect(status().isCreated());

            notifications(memberAuth)
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].type").value(NotificationService.TYPE_TASK_CREATED));
        }

        @Test
        @DisplayName("того, кто ничего не выбирал, чужие задачи по-прежнему не касаются")
        void participatingIsUnaffected() throws Exception {
            createTask("Задача, к которой участник непричастен", null);

            notifications(memberAuth).andExpect(jsonPath("$.items", hasSize(0)));
        }
    }

    // -------------------------------------------------------- настройка и членство

    @Test
    @DisplayName("исключение из проекта уносит настройку с собой — повторное приглашение не воскрешает подписку")
    void removingAMemberForgetsTheSetting() throws Exception {
        setMode(memberAuth, "ALL").andExpect(status().isOk());
        assertThat(settingsRowCount()).isEqualTo(1);

        mockMvc.perform(delete("/api/projects/" + project.getId() + "/members/" + member.getId())
                        .header(AUTHORIZATION, ownerAuth))
                .andExpect(status().isNoContent());

        assertThat(settingsRowCount()).isZero();
    }

    // ------------------------------------------------------------------- фикстуры

    private String settingsPath() {
        return "/api/projects/" + project.getId() + "/notification-settings";
    }

    private ResultActions getMode(String auth) throws Exception {
        return mockMvc.perform(get(settingsPath()).header(AUTHORIZATION, auth));
    }

    private ResultActions setMode(String auth, String mode) throws Exception {
        return mockMvc.perform(put(settingsPath())
                .header(AUTHORIZATION, auth)
                .contentType(APPLICATION_JSON)
                .content("{\"mode\":\"%s\"}".formatted(mode)));
    }

    private ResultActions notifications(String auth) throws Exception {
        return mockMvc.perform(get("/api/notifications").header(AUTHORIZATION, auth))
                .andExpect(status().isOk());
    }

    private ResultActions comment(Task task, String auth, String body) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + task.getId() + "/comments")
                        .header(AUTHORIZATION, auth)
                        .contentType(APPLICATION_JSON)
                        .content("{\"body\":\"%s\"}".formatted(body)))
                .andExpect(status().isCreated());
    }

    /**
     * Задача заводится владельцем и обязательно через API — то есть чужими руками и по тому
     * же пути, что в жизни. Написать её прямо в репозиторий было бы короче, но тогда тест
     * про «наблюдателю приходит новая задача» проверял бы вставку в таблицу, а не рассылку.
     */
    private Task createTask(String title, User assignee) throws Exception {
        String body = assignee != null
                ? """
                {"title":"%s","assigneeId":"%s"}""".formatted(title, assignee.getId())
                : """
                {"title":"%s"}""".formatted(title);
        String response = mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                        .header(AUTHORIZATION, ownerAuth)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID taskId = UUID.fromString(JsonPath.read(response, "$.id"));
        return taskRepository.findById(taskId).orElseThrow();
    }

    private long settingsRowCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project_notification_settings WHERE project_id = ?",
                Long.class, project.getId());
        return count != null ? count : 0L;
    }

    private User createUser(String email, String firstName) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(usernameFrom(email));
        user.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        user.setLastName("Тестов");
        user.setFirstName(firstName);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private void addMember(User user, ProjectRole role) {
        join(project, user, role);
    }

    private void join(Project target, User user, ProjectRole role) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(target);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }
}
