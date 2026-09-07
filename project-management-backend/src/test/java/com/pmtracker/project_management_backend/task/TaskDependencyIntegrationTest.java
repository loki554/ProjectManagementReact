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

import java.util.List;
import java.util.UUID;

import static com.pmtracker.project_management_backend.task.TaskStatus.DONE;
import static com.pmtracker.project_management_backend.task.TaskStatus.IN_PROGRESS;
import static com.pmtracker.project_management_backend.task.TaskStatus.NEW;
import static com.pmtracker.project_management_backend.task.TaskStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Зависимости между задачами (4.8): {@code /api/tasks/{id}/dependencies} и предупреждение
 * при переводе заблокированной задачи в DONE.
 * <p>
 * Половина тестов здесь про то, чего не видно из кода: связь живёт в БД одной строкой, а
 * читается с двух концов, и «добавил блокер — он же появился у соседа в списке
 * заблокированных» проверяемо только на настоящей схеме. Вторая половина — про границы, на
 * которых зависимости ломаются тихо: кольцо блокеров (набор задач, ни одну из которых
 * нельзя закрыть первой), задача в корзине с обеих сторон связи, закрытый блокер, который
 * перестал мешать.
 * <p>
 * Предупреждение проверяется во всех трёх местах, откуда задача попадает в DONE — форма
 * задачи, перетаскивание на доске и массовая правка: это три разных метода сервиса, и
 * забыть проверку в одном из них можно, не сломав два других.
 */
class TaskDependencyIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskDependencyRepository taskDependencyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

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

        project = saveProject("Deps project", "deps-project");
        join(project, owner, ProjectRole.OWNER);
        join(project, viewer, ProjectRole.VIEWER);

        authHeader = "Bearer " + jwtService.generateAccessToken(owner);
        viewerHeader = "Bearer " + jwtService.generateAccessToken(viewer);
        outsiderHeader = "Bearer " + jwtService.generateAccessToken(outsider);
    }

    // ------------------------------------------------------------------- заведение связи

    @Nested
    @DisplayName("заведение связи")
    class Linking {

        /**
         * Главный тест всего пункта: строка в базе одна, а видна она с обеих сторон. Если
         * когда-нибудь связь начнут писать двумя записями, сломается именно это ожидание.
         */
        @Test
        @DisplayName("связь видна с обоих концов: у одной задачи в blockedBy, у другой в blocks")
        void isVisibleFromBothEnds() throws Exception {
            Task api = task("Сделать API", NEW);
            Task ui = task("Сделать экран", NEW);

            link(ui, api).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.blockedBy", hasSize(1)))
                    .andExpect(jsonPath("$.blockedBy[0].taskNumber").value(api.getTaskNumber()))
                    .andExpect(jsonPath("$.blockedBy[0].title").value("Сделать API"))
                    .andExpect(jsonPath("$.blockedBy[0].status").value("NEW"))
                    .andExpect(jsonPath("$.blocks", hasSize(0)));

            dependencies(api).andExpect(status().isOk())
                    .andExpect(jsonPath("$.blocks", hasSize(1)))
                    .andExpect(jsonPath("$.blocks[0].taskNumber").value(ui.getTaskNumber()))
                    .andExpect(jsonPath("$.blockedBy", hasSize(0)));

            assertThat(taskDependencyRepository.count()).isOne();
        }

        @Test
        @DisplayName("блокеры отдаются в порядке номера задачи, а не добавления связи")
        void ordersBlockersByTaskNumber() throws Exception {
            Task first = task("Первая", NEW);
            Task second = task("Вторая", NEW);
            Task third = task("Третья", NEW);

            link(first, third).andExpect(status().isCreated());
            link(first, second).andExpect(status().isCreated());

            dependencies(first)
                    .andExpect(jsonPath("$.blockedBy[0].taskNumber").value(second.getTaskNumber()))
                    .andExpect(jsonPath("$.blockedBy[1].taskNumber").value(third.getTaskNumber()));
        }

        @Test
        @DisplayName("задача не может блокировать сама себя")
        void rejectsSelfDependency() throws Exception {
            Task task = task("Одинокая", NEW);

            link(task, task).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("SELF_DEPENDENCY"));

            assertThat(taskDependencyRepository.count()).isZero();
        }

        @Test
        @DisplayName("задачи разных проектов связать нельзя")
        void rejectsCrossProjectDependency() throws Exception {
            Project other = saveProject("Другой проект", "other-project");
            join(other, owner, ProjectRole.OWNER);
            Task here = task("Здешняя", NEW);
            Task there = saveTask(other, "Тамошняя", NEW);

            link(here, there).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("DEPENDENCY_PROJECT_MISMATCH"));

            assertThat(taskDependencyRepository.count()).isZero();
        }

        @Test
        @DisplayName("повтор той же связи — 409, а не второй строкой")
        void rejectsDuplicate() throws Exception {
            Task blocked = task("Заблокированная", NEW);
            Task blocker = task("Блокер", NEW);

            link(blocked, blocker).andExpect(status().isCreated());
            link(blocked, blocker).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("DUPLICATE_DEPENDENCY"));

            assertThat(taskDependencyRepository.count()).isOne();
        }

        /**
         * Обратная связь дублем не считается: «A блокирует B» и «B блокирует A» — разные
         * рёбра, и отказать в них надо не за дубль, а за кольцо (см. ниже).
         */
        @Test
        @DisplayName("несуществующая задача в качестве блокера — 404")
        void rejectsUnknownBlocker() throws Exception {
            Task blocked = task("Заблокированная", NEW);

            mockMvc.perform(post("/api/tasks/" + blocked.getId() + "/dependencies")
                            .header(AUTHORIZATION, authHeader)
                            .contentType(APPLICATION_JSON)
                            .content("{\"blockerTaskId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));
        }
    }

    // ------------------------------------------------------------------------- кольца

    @Nested
    @DisplayName("кольцо блокеров")
    class Cycles {

        @Test
        @DisplayName("прямое кольцо из двух задач не заводится")
        void rejectsDirectCycle() throws Exception {
            Task first = task("Первая", NEW);
            Task second = task("Вторая", NEW);

            link(first, second).andExpect(status().isCreated());
            link(second, first).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("DEPENDENCY_CYCLE"));

            assertThat(taskDependencyRepository.count()).isOne();
        }

        /**
         * Длинная цепочка — то место, где наивная проверка «а не блокирует ли он меня
         * напрямую» проходит, а граф всё равно замыкается.
         */
        @Test
        @DisplayName("кольцо через цепочку из трёх задач не заводится")
        void rejectsTransitiveCycle() throws Exception {
            Task first = task("Первая", NEW);
            Task second = task("Вторая", NEW);
            Task third = task("Третья", NEW);

            link(second, first).andExpect(status().isCreated());   // 1 блокирует 2
            link(third, second).andExpect(status().isCreated());   // 2 блокирует 3
            link(first, third).andExpect(status().isBadRequest())  // 3 блокировал бы 1
                    .andExpect(jsonPath("$.error").value("DEPENDENCY_CYCLE"));

            assertThat(taskDependencyRepository.count()).isEqualTo(2);
        }

        /**
         * Ромб — не кольцо: две ветки, сходящиеся в одной задаче, закрываются в любом
         * порядке. Отказ здесь означал бы, что обход путает «уже видел» с «замкнулось».
         */
        @Test
        @DisplayName("ромб (две независимые цепочки в одну задачу) — не кольцо")
        void allowsDiamond() throws Exception {
            Task root = task("Общий блокер", NEW);
            Task left = task("Левая ветка", NEW);
            Task right = task("Правая ветка", NEW);
            Task join = task("Сходятся здесь", NEW);

            link(left, root).andExpect(status().isCreated());
            link(right, root).andExpect(status().isCreated());
            link(join, left).andExpect(status().isCreated());
            link(join, right).andExpect(status().isCreated());

            dependencies(join).andExpect(jsonPath("$.blockedBy", hasSize(2)));
        }
    }

    // ------------------------------------------------------------------- снятие связи

    @Nested
    @DisplayName("снятие связи")
    class Unlinking {

        @Test
        @DisplayName("связь снимается, задачи остаются")
        void removesOnlyTheLink() throws Exception {
            Task blocked = task("Заблокированная", NEW);
            Task blocker = task("Блокер", NEW);
            link(blocked, blocker).andExpect(status().isCreated());

            unlink(blocked, blocker).andExpect(status().isOk())
                    .andExpect(jsonPath("$.blockedBy", hasSize(0)));

            assertThat(taskDependencyRepository.count()).isZero();
            assertThat(taskRepository.findById(blocked.getId())).isPresent();
            assertThat(taskRepository.findById(blocker.getId())).isPresent();
        }

        @Test
        @DisplayName("снятие несуществующей связи — 404")
        void rejectsUnknownLink() throws Exception {
            Task blocked = task("Заблокированная", NEW);
            Task other = task("Ни при чём", NEW);

            unlink(blocked, other).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("DEPENDENCY_NOT_FOUND"));
        }

        /**
         * Снять связь можно и с той стороны, где она читается как «блокирую я»: связь одна,
         * и ручка у неё та же, только задачи в ней меняются местами.
         */
        @Test
        @DisplayName("связь снимается и со стороны блокера")
        void removesFromTheBlockerSide() throws Exception {
            Task blocked = task("Заблокированная", NEW);
            Task blocker = task("Блокер", NEW);
            link(blocked, blocker).andExpect(status().isCreated());

            unlink(blocked, blocker).andExpect(status().isOk());

            dependencies(blocker).andExpect(jsonPath("$.blocks", hasSize(0)));
        }
    }

    // ------------------------------------------------------- корзина и закрытые блокеры

    @Nested
    @DisplayName("корзина и закрытые блокеры")
    class Lifecycle {

        @Test
        @DisplayName("физическое удаление задачи уносит её связи")
        void cascadesWithTheTask() throws Exception {
            Task blocked = task("Заблокированная", NEW);
            Task blocker = task("Блокер", NEW);
            link(blocked, blocker).andExpect(status().isCreated());

            jdbcTemplate.update("DELETE FROM tasks WHERE id = ?", blocker.getId());

            assertThat(taskDependencyRepository.count()).isZero();
        }

        /**
         * Мягкое удаление (3.5) — не то же самое: строка связи остаётся, но задача из
         * корзины ни в списке не показывается, ни закрыть соседа не мешает. Восстановление
         * возвращает и её, и связь.
         */
        @Test
        @DisplayName("блокер в корзине выпадает из списка и перестаёт блокировать, а после восстановления возвращается")
        void ignoresTrashedBlockers() throws Exception {
            Task blocked = task("Заблокированная", NEW);
            Task blocker = task("Блокер", NEW);
            link(blocked, blocker).andExpect(status().isCreated());

            mockMvc.perform(delete("/api/tasks/" + blocker.getId()).header(AUTHORIZATION, authHeader))
                    .andExpect(status().isNoContent());

            dependencies(blocked).andExpect(jsonPath("$.blockedBy", hasSize(0)));
            assertThat(taskDependencyRepository.count()).isOne();
            moveToDone(blocked).andExpect(status().isOk());

            mockMvc.perform(post("/api/tasks/" + blocker.getId() + "/restore").header(AUTHORIZATION, authHeader))
                    .andExpect(status().isOk());
            dependencies(blocked).andExpect(jsonPath("$.blockedBy", hasSize(1)));
        }

        @Test
        @DisplayName("закрытый блокер остаётся в списке, но перестаёт мешать")
        void closedBlockerStopsBlocking() throws Exception {
            Task blocked = task("Заблокированная", NEW);
            Task blocker = task("Блокер", DONE);
            link(blocked, blocker).andExpect(status().isCreated());

            dependencies(blocked)
                    .andExpect(jsonPath("$.blockedBy", hasSize(1)))
                    .andExpect(jsonPath("$.blockedBy[0].status").value("DONE"));
            taskByNumber(blocked).andExpect(jsonPath("$.openBlockerCount").value(0));
            moveToDone(blocked).andExpect(status().isOk());
        }

        /**
         * REJECTED — тоже «закрыта»: от задачи отказались, и держать из-за неё соседа
         * незачем. Тот же список статусов, по которому не шлются напоминания о дедлайне.
         */
        @Test
        @DisplayName("отклонённый блокер тоже не мешает")
        void rejectedBlockerStopsBlocking() throws Exception {
            Task blocked = task("Заблокированная", NEW);
            link(blocked, task("Передумали", REJECTED)).andExpect(status().isCreated());

            taskByNumber(blocked).andExpect(jsonPath("$.openBlockerCount").value(0));
        }
    }

    // ------------------------------------------------- предупреждение при переводе в DONE

    @Nested
    @DisplayName("предупреждение при закрытии")
    class DoneWarning {

        @Test
        @DisplayName("форма задачи: 409 на DONE с открытым блокером, ignoreBlockers проводит правку")
        void warnsOnTaskForm() throws Exception {
            Task blocked = task("Заблокированная", IN_PROGRESS);
            Task blocker = task("Блокер", IN_PROGRESS);
            link(blocked, blocker).andExpect(status().isCreated());

            updateTask(blocked, DONE, false).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TASK_HAS_OPEN_BLOCKERS"));
            assertThat(statusOf(blocked)).isEqualTo(IN_PROGRESS);

            updateTask(blocked, DONE, true).andExpect(status().isOk());
            assertThat(statusOf(blocked)).isEqualTo(DONE);
        }

        /**
         * Отказ обязан не оставить следов: он стоит до первой правки, поэтому ни поля, ни
         * лента активности не должны сдвинуться. Проверяется по названию — оно едет в том
         * же запросе, что и статус.
         */
        @Test
        @DisplayName("отказ не применяет остальные поля запроса")
        void rejectedFormChangesNothing() throws Exception {
            Task blocked = task("Старое название", IN_PROGRESS);
            link(blocked, task("Блокер", NEW)).andExpect(status().isCreated());

            mockMvc.perform(patch("/api/tasks/" + blocked.getId())
                            .header(AUTHORIZATION, authHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Новое название","status":"DONE","urgency":"MEDIUM","version":0}"""))
                    .andExpect(status().isConflict());

            Task reloaded = taskRepository.findById(blocked.getId()).orElseThrow();
            assertThat(reloaded.getTitle()).isEqualTo("Старое название");
            assertThat(reloaded.getStatus()).isEqualTo(IN_PROGRESS);
        }

        @Test
        @DisplayName("доска: 409 на перетаскивание в DONE, ignoreBlockers проводит перенос")
        void warnsOnBoardDrag() throws Exception {
            Task blocked = task("Заблокированная", IN_PROGRESS);
            link(blocked, task("Блокер", NEW)).andExpect(status().isCreated());

            moveToDone(blocked).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TASK_HAS_OPEN_BLOCKERS"));
            assertThat(statusOf(blocked)).isEqualTo(IN_PROGRESS);

            mockMvc.perform(patch("/api/tasks/" + blocked.getId() + "/status")
                            .header(AUTHORIZATION, authHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"status":"DONE","position":0,"expectedStatus":"IN_PROGRESS","ignoreBlockers":true}"""))
                    .andExpect(status().isOk());
            assertThat(statusOf(blocked)).isEqualTo(DONE);
        }

        @Test
        @DisplayName("массовая правка: 409 на весь набор, ни одна задача не закрывается")
        void warnsOnBulkUpdate() throws Exception {
            Task free = task("Свободная", NEW);
            Task blocked = task("Заблокированная", NEW);
            link(blocked, task("Блокер", NEW)).andExpect(status().isCreated());

            bulkDone(List.of(free, blocked), false).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TASK_HAS_OPEN_BLOCKERS"));
            assertThat(statusOf(free)).isEqualTo(NEW);
            assertThat(statusOf(blocked)).isEqualTo(NEW);

            bulkDone(List.of(free, blocked), true).andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(2));
            assertThat(statusOf(blocked)).isEqualTo(DONE);
        }

        /**
         * Уже закрытая задача остаётся полностью управляемой: её двигают внутри колонки и
         * правят по описанию. Отказ здесь означал бы, что задачу с блокером, однажды
         * закрытую, больше нельзя тронуть вообще.
         */
        @Test
        @DisplayName("задача, уже стоящая в DONE, правится и переставляется без вопросов")
        void alreadyDoneTaskIsNotBlocked() throws Exception {
            Task blocked = task("Давно закрытая", DONE);
            link(blocked, task("Блокер", NEW)).andExpect(status().isCreated());

            updateTask(blocked, DONE, false).andExpect(status().isOk());
        }

        /**
         * REJECTED в этом смысле не «закрытие»: от задачи отказались, и требовать сначала
         * доделать то, что ей мешало, было бы прямо наоборот.
         */
        @Test
        @DisplayName("перевод в REJECTED вопросов не задаёт")
        void rejectingIsNotBlocked() throws Exception {
            Task blocked = task("Заблокированная", IN_PROGRESS);
            link(blocked, task("Блокер", NEW)).andExpect(status().isCreated());

            updateTask(blocked, REJECTED, false).andExpect(status().isOk());
            assertThat(statusOf(blocked)).isEqualTo(REJECTED);
        }

        @Test
        @DisplayName("незакрытые блокеры считаются в openBlockerCount и в списке, и в карточке")
        void reportsOpenBlockerCount() throws Exception {
            Task blocked = task("Заблокированная", NEW);
            link(blocked, task("Первый блокер", NEW)).andExpect(status().isCreated());
            link(blocked, task("Второй блокер", IN_PROGRESS)).andExpect(status().isCreated());
            link(blocked, task("Третий, закрытый", DONE)).andExpect(status().isCreated());

            taskByNumber(blocked).andExpect(jsonPath("$.openBlockerCount").value(2));
            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, authHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.taskNumber == " + blocked.getTaskNumber() + ")].openBlockerCount")
                            .value(2));
        }
    }

    // ------------------------------------------------------------------------- права

    @Nested
    @DisplayName("права")
    class Access {

        @Test
        @DisplayName("VIEWER читает связи, но не заводит их")
        void viewerReadsButDoesNotWrite() throws Exception {
            Task blocked = task("Заблокированная", NEW);
            Task blocker = task("Блокер", NEW);
            link(blocked, blocker).andExpect(status().isCreated());

            mockMvc.perform(get("/api/tasks/" + blocked.getId() + "/dependencies")
                            .header(AUTHORIZATION, viewerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.blockedBy", hasSize(1)));

            mockMvc.perform(post("/api/tasks/" + blocked.getId() + "/dependencies")
                            .header(AUTHORIZATION, viewerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("{\"blockerTaskId\":\"" + blocker.getId() + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        }

        @Test
        @DisplayName("посторонний не видит связей чужого проекта")
        void outsiderSeesNothing() throws Exception {
            Task blocked = task("Заблокированная", NEW);

            mockMvc.perform(get("/api/tasks/" + blocked.getId() + "/dependencies")
                            .header(AUTHORIZATION, outsiderHeader))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("NOT_A_PROJECT_MEMBER"));
        }
    }

    // ------------------------------------------------------------------------ хелперы

    /** «blocked заблокирована blocker» — то же направление, что у POST на самой ручке. */
    private ResultActions link(Task blocked, Task blocker) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + blocked.getId() + "/dependencies")
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content("{\"blockerTaskId\":\"" + blocker.getId() + "\"}"));
    }

    private ResultActions unlink(Task blocked, Task blocker) throws Exception {
        return mockMvc.perform(delete("/api/tasks/" + blocked.getId() + "/dependencies/" + blocker.getId())
                .header(AUTHORIZATION, authHeader));
    }

    private ResultActions dependencies(Task task) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + task.getId() + "/dependencies")
                .header(AUTHORIZATION, authHeader));
    }

    private ResultActions taskByNumber(Task task) throws Exception {
        return mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks/by-number/" + task.getTaskNumber())
                .header(AUTHORIZATION, authHeader));
    }

    private ResultActions updateTask(Task task, TaskStatus status, boolean ignoreBlockers) throws Exception {
        Task current = taskRepository.findById(task.getId()).orElseThrow();
        return mockMvc.perform(patch("/api/tasks/" + task.getId())
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"title":"%s","status":"%s","urgency":"MEDIUM","version":%d,"ignoreBlockers":%s}"""
                        .formatted(current.getTitle(), status, current.getVersion(), ignoreBlockers)));
    }

    private ResultActions moveToDone(Task task) throws Exception {
        Task current = taskRepository.findById(task.getId()).orElseThrow();
        return mockMvc.perform(patch("/api/tasks/" + task.getId() + "/status")
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"status":"DONE","position":0,"expectedStatus":"%s"}""".formatted(current.getStatus())));
    }

    private ResultActions bulkDone(List<Task> tasks, boolean ignoreBlockers) throws Exception {
        String ids = tasks.stream().map(t -> "\"" + t.getId() + "\"").collect(java.util.stream.Collectors.joining(","));
        return mockMvc.perform(patch("/api/projects/" + project.getId() + "/tasks/bulk")
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"taskIds":[%s],"status":"DONE","ignoreBlockers":%s}""".formatted(ids, ignoreBlockers)));
    }

    private TaskStatus statusOf(Task task) {
        return taskRepository.findById(task.getId()).orElseThrow().getStatus();
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

    private Task task(String title, TaskStatus status) {
        return saveTask(project, title, status);
    }

    private Task saveTask(Project target, String title, TaskStatus status) {
        Task task = new Task();
        task.setProject(target);
        task.setTitle(title);
        task.setStatus(status);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setPosition(0);
        task.setCreatedBy(owner);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(target.getId()));
        return taskRepository.save(task);
    }
}
