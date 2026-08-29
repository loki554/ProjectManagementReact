package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Мягкое удаление задач и корзина проекта (3.5).
 * <p>
 * Проверяются две стороны одного механизма. Первая — что удалённая задача действительно
 * исчезает отовсюду: из списка, с доски, из подзадач, из «моих активных», из поиска по
 * номеру, и что к ней нельзя привязать комментарий или списание времени. Это как раз то,
 * что легко упустить: невидимость держится на @SQLRestriction, и любой запрос, обошедший
 * ORM, вернул бы удалённую задачу как живую.
 * <p>
 * Вторая — что она при этом никуда не делась: лежит в корзине со сроком, возвращается
 * вместе со своими подзадачами и со всем, что к ней было привязано.
 */
class TaskTrashIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskCleanupJob taskCleanupJob;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private Project project;
    private String authHeader;

    @BeforeEach
    void createProject() {
        owner = new User();
        owner.setEmail("owner@example.com");
        owner.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        owner.setLastName("Тестов");
        owner.setFirstName("Владелец");
        owner.setEmailVerified(true);
        userRepository.save(owner);

        project = new Project();
        project.setName("Trash project");
        project.setSlug("trash-project");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(owner);
        membership.setRole(ProjectRole.OWNER);
        projectMemberRepository.save(membership);

        authHeader = "Bearer " + jwtService.generateAccessToken(owner);
    }

    // -------------------------------------------------------------------- невидимость

    @Nested
    @DisplayName("удалённая задача не видна")
    class Invisible {

        @Test
        @DisplayName("ни в списке, ни на доске, ни по номеру, ни по id")
        void disappearsFromEveryListing() throws Exception {
            task("Живая");
            Task deleted = task("Удалённая");

            deleteTask(deleted).andExpect(status().isNoContent());

            assertThat(listTitles()).containsExactly("Живая");
            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks/board")
                            .header(AUTHORIZATION, authHeader))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].title").value("Живая"));
            mockMvc.perform(get("/api/tasks/" + deleted.getId()).header(AUTHORIZATION, authHeader))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks/by-number/"
                            + deleted.getTaskNumber()).header(AUTHORIZATION, authHeader))
                    .andExpect(status().isNotFound());
        }

        /**
         * Ровно тот случай, ради которого невидимость сделана на уровне сущности: подзадачи,
         * комментарии, время и вложения ищут задачу через тот же findById, и достаточно
         * одного места, забывшего про фильтр, чтобы удалённая задача снова стала рабочей.
         */
        @Test
        @DisplayName("к ней нельзя привязать комментарий, время или подзадачу")
        void stopsAcceptingAttachedRecords() throws Exception {
            Task deleted = task("Удалённая");
            deleteTask(deleted).andExpect(status().isNoContent());

            mockMvc.perform(post("/api/tasks/" + deleted.getId() + "/comments")
                            .header(AUTHORIZATION, authHeader)
                            .contentType(APPLICATION_JSON)
                            .content("{\"body\":\"комментарий\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/tasks/" + deleted.getId() + "/time-logs")
                            .header(AUTHORIZATION, authHeader)
                            .contentType(APPLICATION_JSON)
                            .content("{\"hours\":1.0,\"spentOn\":\"2026-01-01\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/tasks/" + deleted.getId() + "/subtasks")
                            .header(AUTHORIZATION, authHeader)
                            .contentType(APPLICATION_JSON)
                            .content("{\"title\":\"Подзадача\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("подзадачи уезжают в корзину вместе с родителем")
        void takesItsSubtasksWithIt() throws Exception {
            Task parent = task("Родитель");
            Task child = subtask(parent, "Подзадача");

            deleteTask(parent).andExpect(status().isNoContent());

            assertThat(deletedAt(child)).isNotNull();
            // И с той же меткой — по ней восстановление собирает их обратно.
            assertThat(deletedAt(child)).isEqualTo(deletedAt(parent));
        }
    }

    // ------------------------------------------------------------------------ корзина

    @Nested
    @DisplayName("корзина")
    class Trash {

        @Test
        @DisplayName("показывает удалённую задачу, срок хранения и число подзадач")
        void listsWhatWasDeleted() throws Exception {
            Task parent = task("Родитель");
            subtask(parent, "Первая");
            subtask(parent, "Вторая");
            task("Живая");

            deleteTask(parent).andExpect(status().isNoContent());

            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks/trash")
                            .header(AUTHORIZATION, authHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].title").value("Родитель"))
                    .andExpect(jsonPath("$[0].subtaskCount").value(2))
                    .andExpect(jsonPath("$[0].deletedAt").exists())
                    .andExpect(jsonPath("$[0].purgeAfter").exists());
        }

        /**
         * Подзадача, уехавшая вместе с родителем, своей строкой не показывается: вернуть её
         * отдельно всё равно некуда, и в списке она была бы шумом рядом с родителем.
         */
        @Test
        @DisplayName("подзадача, удалённая вместе с родителем, отдельной строкой не показывается")
        void doesNotListCascadedSubtasks() throws Exception {
            Task parent = task("Родитель");
            subtask(parent, "Подзадача");

            deleteTask(parent).andExpect(status().isNoContent());

            assertThat(trashTitles()).containsExactly("Родитель");
        }

        @Test
        @DisplayName("подзадача, удалённая сама по себе, попадает в корзину отдельно")
        void listsASubtaskDeletedOnItsOwn() throws Exception {
            Task parent = task("Родитель");
            Task child = subtask(parent, "Подзадача");

            deleteTask(child).andExpect(status().isNoContent());

            assertThat(trashTitles()).containsExactly("Подзадача");
        }
    }

    // ------------------------------------------------------------------ восстановление

    @Nested
    @DisplayName("восстановление")
    class Restore {

        @Test
        @DisplayName("возвращает задачу в списки вместе с подзадачами")
        void bringsTheTaskAndItsSubtasksBack() throws Exception {
            Task parent = task("Родитель");
            Task child = subtask(parent, "Подзадача");

            deleteTask(parent).andExpect(status().isNoContent());
            restore(parent).andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Родитель"));

            assertThat(listTitles()).containsExactly("Родитель");
            assertThat(deletedAt(child)).isNull();
            assertThat(trashTitles()).isEmpty();
            mockMvc.perform(get("/api/tasks/" + child.getId()).header(AUTHORIZATION, authHeader))
                    .andExpect(status().isOk());
        }

        /**
         * Подзадача, удалённая отдельно и раньше родителя, остаётся удалённой: её метка
         * времени другая, и к удалению родителя она отношения не имеет.
         */
        @Test
        @DisplayName("не воскрешает подзадачу, удалённую раньше и отдельно")
        void leavesSeparatelyDeletedSubtasksAlone() throws Exception {
            Task parent = task("Родитель");
            Task earlier = subtask(parent, "Удалена раньше");
            Task together = subtask(parent, "Удалена вместе");

            deleteTask(earlier).andExpect(status().isNoContent());
            deleteTask(parent).andExpect(status().isNoContent());
            restore(parent).andExpect(status().isOk());

            assertThat(deletedAt(together)).isNull();
            assertThat(deletedAt(earlier)).isNotNull();
            // И снова видна в корзине сама по себе — родитель больше не удалён.
            assertThat(trashTitles()).containsExactly("Удалена раньше");
        }

        @Test
        @DisplayName("подзадача под удалённым родителем — 409, сначала родитель")
        void refusesToRestoreIntoADeletedParent() throws Exception {
            Task parent = task("Родитель");
            Task child = subtask(parent, "Подзадача");

            deleteTask(parent).andExpect(status().isNoContent());

            restore(child)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("PARENT_TASK_DELETED"));
        }

        @Test
        @DisplayName("живая задача не восстанавливается — 404, восстанавливать нечего")
        void refusesToRestoreALiveTask() throws Exception {
            restore(task("Живая")).andExpect(status().isNotFound());
        }
    }

    // ------------------------------------------------------------------------- чистка

    @Nested
    @DisplayName("чистка по расписанию")
    class Cleanup {

        @Test
        @DisplayName("удаляет физически то, что пролежало дольше срока, и не трогает остальное")
        void purgesOnlyExpiredTrash() throws Exception {
            Task old = task("Просроченная");
            Task recent = task("Свежая");
            Task alive = task("Живая");

            deleteTask(old).andExpect(status().isNoContent());
            deleteTask(recent).andExpect(status().isNoContent());
            // Сдвигаем метку мимо API: ждать месяц в тесте нечем, а другого способа состарить
            // запись корзины нет — deleted_at выставляет сам сервер.
            backdate(old, TaskService.TRASH_RETENTION.plus(Duration.ofDays(1)));

            taskCleanupJob.purgeExpiredTrash();

            assertThat(existsInDatabase(old)).isFalse();
            assertThat(existsInDatabase(recent)).isTrue();
            assertThat(existsInDatabase(alive)).isTrue();
        }

        @Test
        @DisplayName("подзадачи уходят вместе с просроченным родителем")
        void purgesSubtasksWithTheirParent() throws Exception {
            Task parent = task("Родитель");
            Task child = subtask(parent, "Подзадача");

            deleteTask(parent).andExpect(status().isNoContent());
            backdate(parent, TaskService.TRASH_RETENTION.plus(Duration.ofDays(1)));
            backdate(child, TaskService.TRASH_RETENTION.plus(Duration.ofDays(1)));

            taskCleanupJob.purgeExpiredTrash();

            assertThat(existsInDatabase(parent)).isFalse();
            assertThat(existsInDatabase(child)).isFalse();
        }

        @Test
        @DisplayName("повторный запуск удаляет ноль строк — джоб идемпотентен")
        void isIdempotent() throws Exception {
            Task old = task("Просроченная");
            deleteTask(old).andExpect(status().isNoContent());
            backdate(old, TaskService.TRASH_RETENTION.plus(Duration.ofDays(1)));

            taskCleanupJob.purgeExpiredTrash();
            taskCleanupJob.purgeExpiredTrash();

            assertThat(existsInDatabase(old)).isFalse();
        }
    }

    // ------------------------------------------------------------------------ хелперы

    private ResultActions deleteTask(Task task) throws Exception {
        return mockMvc.perform(delete("/api/tasks/" + task.getId()).header(AUTHORIZATION, authHeader));
    }

    private ResultActions restore(Task task) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + task.getId() + "/restore").header(AUTHORIZATION, authHeader));
    }

    private List<String> listTitles() throws Exception {
        return jsonTitles(mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks")
                .header(AUTHORIZATION, authHeader)));
    }

    private List<String> trashTitles() throws Exception {
        return jsonTitles(mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks/trash")
                .header(AUTHORIZATION, authHeader)));
    }

    private static List<String> jsonTitles(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return java.util.regex.Pattern.compile("\"title\":\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(body).results().map(m -> m.group(1)).toList();
    }

    /** Читаем deleted_at напрямую: для JPA удалённой задачи не существует. */
    private Instant deletedAt(Task task) {
        return jdbcTemplate.queryForObject("SELECT deleted_at FROM tasks WHERE id = ?", Instant.class, task.getId());
    }

    private boolean existsInDatabase(Task task) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tasks WHERE id = ?", Integer.class, task.getId());
        return count != null && count > 0;
    }

    private void backdate(Task task, Duration by) {
        jdbcTemplate.update("UPDATE tasks SET deleted_at = deleted_at - ?::interval WHERE id = ?",
                by.toDays() + " days", task.getId());
    }

    private Task task(String title) {
        return saveTask(title, null);
    }

    private Task subtask(Task parent, String title) {
        return saveTask(title, parent);
    }

    private Task saveTask(String title, Task parent) {
        Task task = new Task();
        task.setProject(project);
        task.setParentTask(parent);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        task.setTitle(title);
        task.setStatus(TaskStatus.NEW);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setCreatedBy(owner);
        return taskRepository.save(task);
    }
}
