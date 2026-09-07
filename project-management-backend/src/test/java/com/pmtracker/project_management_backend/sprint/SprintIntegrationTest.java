package com.pmtracker.project_management_backend.sprint;

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
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.UUID;

import static com.pmtracker.project_management_backend.task.TaskStatus.DONE;
import static com.pmtracker.project_management_backend.task.TaskStatus.IN_PROGRESS;
import static com.pmtracker.project_management_backend.task.TaskStatus.NEW;
import static com.pmtracker.project_management_backend.task.TaskStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
 * Спринты (4.9): {@code /api/projects/{id}/sprints}, {@code /api/sprints/{id}} и связь
 * спринта с задачей.
 *
 * <p>Проверяется в основном то, чего не видно из кода одного класса. Во-первых, границы,
 * на которых спринт ломается тихо: два активных спринта в проекте (тогда «текущий спринт»
 * перестаёт быть одним), закрытие спринта с недоделанными задачами (они обязаны куда-то
 * переехать, иначе потеряются в архиве), удаление спринта (задачи обязаны выжить).
 * Во-вторых, права: чтение спринтов — у всех, включая VIEWER, а управление ими — у ADMIN,
 * при том что положить задачу в спринт может любой MEMBER; три разные роли в одном пункте
 * легко перепутать местами, не сломав ничего заметного.
 */
class SprintIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private SprintRepository sprintRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private User member;
    private User viewer;
    private Project project;
    private String ownerHeader;
    private String memberHeader;
    private String viewerHeader;

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 18);

    @BeforeEach
    void createProject() {
        owner = saveUser("owner@example.com", "Яковлев", "Олег");
        member = saveUser("member@example.com", "Петров", "Пётр");
        viewer = saveUser("viewer@example.com", "Абрамов", "Игорь");

        project = saveProject("Sprint project", "sprint-project");
        join(project, owner, ProjectRole.OWNER);
        join(project, member, ProjectRole.MEMBER);
        join(project, viewer, ProjectRole.VIEWER);

        ownerHeader = "Bearer " + jwtService.generateAccessToken(owner);
        memberHeader = "Bearer " + jwtService.generateAccessToken(member);
        viewerHeader = "Bearer " + jwtService.generateAccessToken(viewer);
    }

    // ------------------------------------------------------------------ заведение и правка

    @Nested
    @DisplayName("заведение спринта")
    class Creating {

        @Test
        @DisplayName("спринт заводится в статусе PLANNED и с пустым прогрессом")
        void createsPlannedSprint() throws Exception {
            createSprint("Спринт 1", MONDAY, FRIDAY).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Спринт 1"))
                    .andExpect(jsonPath("$.status").value("PLANNED"))
                    .andExpect(jsonPath("$.startDate").value("2026-09-07"))
                    .andExpect(jsonPath("$.endDate").value("2026-09-18"))
                    .andExpect(jsonPath("$.startedAt").value(nullValue()))
                    .andExpect(jsonPath("$.taskCount").value(0))
                    .andExpect(jsonPath("$.closedTaskCount").value(0));
        }

        /**
         * Майлстоун — это не отдельная сущность, а спринт со схлопнутым окном. Тест
         * фиксирует именно это: одинаковые даты проходят проверку, а не считаются ошибкой.
         */
        @Test
        @DisplayName("майлстоун — спринт с одинаковыми датами начала и конца")
        void allowsSingleDayWindow() throws Exception {
            createSprint("Релиз 2.0", FRIDAY, FRIDAY).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.startDate").value("2026-09-18"))
                    .andExpect(jsonPath("$.endDate").value("2026-09-18"));
        }

        @Test
        @DisplayName("окончание раньше начала — 400")
        void rejectsInvertedWindow() throws Exception {
            createSprint("Спринт 1", FRIDAY, MONDAY).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("SPRINT_DATES_INVALID"));

            assertThat(sprintRepository.count()).isZero();
        }

        @Test
        @DisplayName("имя уникально в проекте")
        void rejectsDuplicateName() throws Exception {
            createSprint("Спринт 1", MONDAY, FRIDAY).andExpect(status().isCreated());

            createSprint("Спринт 1", MONDAY, FRIDAY).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("DUPLICATE_SPRINT_NAME"));

            assertThat(sprintRepository.count()).isOne();
        }

        @Test
        @DisplayName("цель из пробелов сохраняется как «цели нет»")
        void normalizesBlankGoal() throws Exception {
            mockMvc.perform(post("/api/projects/" + project.getId() + "/sprints")
                            .header(AUTHORIZATION, ownerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Спринт 1","goal":"   ","startDate":"2026-09-07","endDate":"2026-09-18"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.goal").value(nullValue()));
        }
    }

    @Nested
    @DisplayName("права")
    class Permissions {

        @Test
        @DisplayName("VIEWER видит спринты, но не заводит их")
        void viewerReadsButDoesNotWrite() throws Exception {
            createSprint("Спринт 1", MONDAY, FRIDAY).andExpect(status().isCreated());

            mockMvc.perform(get("/api/projects/" + project.getId() + "/sprints")
                            .header(AUTHORIZATION, viewerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));

            mockMvc.perform(post("/api/projects/" + project.getId() + "/sprints")
                            .header(AUTHORIZATION, viewerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Свой","startDate":"2026-09-07","endDate":"2026-09-18"}"""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        }

        /**
         * Граница, ради которой этот тест и написан: MEMBER распоряжается задачами, но не
         * планом. Положить задачу в спринт он может (см. {@link TaskLink}), а завести или
         * закрыть спринт — нет.
         */
        @Test
        @DisplayName("MEMBER не заводит и не закрывает спринты")
        void memberDoesNotManageSprints() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);

            mockMvc.perform(post("/api/projects/" + project.getId() + "/sprints")
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Свой","startDate":"2026-09-07","endDate":"2026-09-18"}"""))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/api/sprints/" + sprintId + "/start").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isForbidden());

            mockMvc.perform(delete("/api/sprints/" + sprintId).header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isForbidden());

            assertThat(sprintRepository.findById(sprintId).orElseThrow().getStatus())
                    .isEqualTo(SprintStatus.PLANNED);
        }

        @Test
        @DisplayName("посторонний не видит спринты чужого проекта")
        void outsiderIsRejected() throws Exception {
            User outsider = saveUser("outsider@example.com", "Чужой", "Человек");
            String outsiderHeader = "Bearer " + jwtService.generateAccessToken(outsider);

            mockMvc.perform(get("/api/projects/" + project.getId() + "/sprints")
                            .header(AUTHORIZATION, outsiderHeader))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("NOT_A_PROJECT_MEMBER"));
        }
    }

    // ----------------------------------------------------------------------- жизненный цикл

    @Nested
    @DisplayName("жизненный цикл")
    class Lifecycle {

        @Test
        @DisplayName("активный спринт в проекте может быть только один")
        void allowsSingleActiveSprint() throws Exception {
            UUID first = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            UUID second = createdSprintId("Спринт 2", FRIDAY, FRIDAY.plusWeeks(2));

            start(first).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            start(second).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("SPRINT_ALREADY_ACTIVE"));

            assertThat(sprintRepository.findById(second).orElseThrow().getStatus())
                    .isEqualTo(SprintStatus.PLANNED);
        }

        @Test
        @DisplayName("закрытый спринт нельзя ни начать заново, ни закрыть повторно")
        void transitionsAreOneWay() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            start(sprintId).andExpect(status().isOk());
            complete(sprintId, null).andExpect(status().isOk());

            start(sprintId).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("SPRINT_TRANSITION_INVALID"));
            complete(sprintId, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("SPRINT_TRANSITION_INVALID"));
        }

        @Test
        @DisplayName("незапущенный спринт нельзя закрыть")
        void doesNotCompletePlannedSprint() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);

            complete(sprintId, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("SPRINT_TRANSITION_INVALID"));
        }

        /**
         * Освободившийся слот: закрыли один — можно начать следующий. Без этого теста
         * «только один активный» легко превратился бы в «активный только первый».
         */
        @Test
        @DisplayName("после закрытия спринта можно начать следующий")
        void freesTheActiveSlot() throws Exception {
            UUID first = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            UUID second = createdSprintId("Спринт 2", FRIDAY, FRIDAY.plusWeeks(2));

            start(first).andExpect(status().isOk());
            complete(first, null).andExpect(status().isOk());
            start(second).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("правка карточки не трогает статус и работает на закрытом спринте")
        void updateKeepsStatus() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            start(sprintId).andExpect(status().isOk());
            complete(sprintId, null).andExpect(status().isOk());

            mockMvc.perform(put("/api/sprints/" + sprintId)
                            .header(AUTHORIZATION, ownerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Спринт 1 (сентябрь)","goal":"Выкатить поиск","startDate":"2026-09-07","endDate":"2026-09-18"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Спринт 1 (сентябрь)"))
                    .andExpect(jsonPath("$.goal").value("Выкатить поиск"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }
    }

    // -------------------------------------------------------------------- закрытие спринта

    @Nested
    @DisplayName("закрытие спринта")
    class Completing {

        @Test
        @DisplayName("незакрытые задачи уезжают в бэклог, закрытые остаются в спринте")
        void movesUnfinishedToBacklog() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            start(sprintId).andExpect(status().isOk());

            Task done = taskInSprint("Сделанная", DONE, sprintId);
            Task rejected = taskInSprint("Отклонённая", REJECTED, sprintId);
            Task inProgress = taskInSprint("В работе", IN_PROGRESS, sprintId);
            Task fresh = taskInSprint("Новая", NEW, sprintId);

            complete(sprintId, null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.movedTasks").value(2))
                    .andExpect(jsonPath("$.sprint.status").value("COMPLETED"));

            assertThat(sprintIdOf(done)).isEqualTo(sprintId);
            assertThat(sprintIdOf(rejected)).isEqualTo(sprintId);
            assertThat(sprintIdOf(inProgress)).isNull();
            assertThat(sprintIdOf(fresh)).isNull();
        }

        @Test
        @DisplayName("незакрытые задачи можно перенести в следующий спринт")
        void movesUnfinishedToNextSprint() throws Exception {
            UUID current = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            UUID next = createdSprintId("Спринт 2", FRIDAY, FRIDAY.plusWeeks(2));
            start(current).andExpect(status().isOk());

            Task carried = taskInSprint("Не успели", IN_PROGRESS, current);
            Task closed = taskInSprint("Успели", DONE, current);

            complete(current, next).andExpect(status().isOk())
                    .andExpect(jsonPath("$.movedTasks").value(1));

            assertThat(sprintIdOf(carried)).isEqualTo(next);
            assertThat(sprintIdOf(closed)).isEqualTo(current);
        }

        @Test
        @DisplayName("перенести в сам закрываемый спринт нельзя")
        void rejectsMovingIntoItself() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            start(sprintId).andExpect(status().isOk());
            Task task = taskInSprint("Не успели", NEW, sprintId);

            complete(sprintId, sprintId).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("SPRINT_TRANSITION_INVALID"));

            // Отказ не должен закрыть спринт наполовину: ни статуса, ни переезда задач.
            assertThat(sprintRepository.findById(sprintId).orElseThrow().getStatus())
                    .isEqualTo(SprintStatus.ACTIVE);
            assertThat(sprintIdOf(task)).isEqualTo(sprintId);
        }

        @Test
        @DisplayName("перенести в спринт чужого проекта нельзя")
        void rejectsMovingToForeignSprint() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            start(sprintId).andExpect(status().isOk());

            Project other = saveProject("Other project", "other-project");
            join(other, owner, ProjectRole.OWNER);
            UUID foreign = createdSprintId(other, "Чужой спринт", MONDAY, FRIDAY);

            complete(sprintId, foreign).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("SPRINT_PROJECT_MISMATCH"));
        }

        @Test
        @DisplayName("в ленте — одно событие на спринт и по событию на каждую переехавшую задачу")
        void writesActivity() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            start(sprintId).andExpect(status().isOk());
            taskInSprint("Не успели", NEW, sprintId);
            taskInSprint("Тоже не успели", IN_PROGRESS, sprintId);
            taskInSprint("Успели", DONE, sprintId);

            complete(sprintId, null).andExpect(status().isOk());

            assertThat(activityCount("sprint_created")).isOne();
            assertThat(activityCount("sprint_started")).isOne();
            assertThat(activityCount("sprint_completed")).isOne();
            assertThat(activityCount("task_sprint_changed")).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------------ спринт задачи

    @Nested
    @DisplayName("спринт задачи")
    class TaskLink {

        @Test
        @DisplayName("MEMBER кладёт задачу в спринт при создании и вынимает правкой")
        void memberAssignsAndClears() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);

            String created = mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Задача","sprintId":"%s"}""".formatted(sprintId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sprint.id").value(sprintId.toString()))
                    .andExpect(jsonPath("$.sprint.name").value("Спринт 1"))
                    .andExpect(jsonPath("$.sprint.status").value("PLANNED"))
                    .andReturn().getResponse().getContentAsString();

            UUID taskId = UUID.fromString(jsonField(created, "id"));
            long version = Long.parseLong(jsonField(created, "version"));

            // Спринт не прислали — значит его сняли: форма задачи присылает своё состояние
            // целиком, как и с тэгом.
            mockMvc.perform(patch("/api/tasks/" + taskId)
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Задача","status":"NEW","urgency":"MEDIUM","version":%d}""".formatted(version)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sprint").value(nullValue()));
        }

        @Test
        @DisplayName("спринт чужого проекта — 400")
        void rejectsForeignSprint() throws Exception {
            Project other = saveProject("Other project", "other-project");
            join(other, owner, ProjectRole.OWNER);
            UUID foreign = createdSprintId(other, "Чужой спринт", MONDAY, FRIDAY);

            mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Задача","sprintId":"%s"}""".formatted(foreign)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("SPRINT_PROJECT_MISMATCH"));
        }

        @Test
        @DisplayName("в завершённый спринт задачу не положить")
        void rejectsCompletedSprint() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            start(sprintId).andExpect(status().isOk());
            complete(sprintId, null).andExpect(status().isOk());

            mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Задача","sprintId":"%s"}""".formatted(sprintId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("SPRINT_COMPLETED"));
        }

        /**
         * Обратная сторона предыдущего теста и причина, по которой resolveForTask знает
         * текущий спринт задачи: у задачи, оставшейся в завершённом спринте, обязана
         * работать обычная правка — иначе закрытие спринта замораживало бы его задачи.
         */
        @Test
        @DisplayName("задачу из завершённого спринта можно править, не вынимая её оттуда")
        void allowsEditingTaskInCompletedSprint() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            start(sprintId).andExpect(status().isOk());
            Task task = taskInSprint("Успели", DONE, sprintId);
            complete(sprintId, null).andExpect(status().isOk());

            long version = taskRepository.findById(task.getId()).orElseThrow().getVersion();
            mockMvc.perform(patch("/api/tasks/" + task.getId())
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Успели, но переименовали","status":"DONE","urgency":"MEDIUM","sprintId":"%s","version":%d}"""
                                    .formatted(sprintId, version)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sprint.id").value(sprintId.toString()));
        }

        @Test
        @DisplayName("массовая правка кладёт выделенные задачи в спринт и вынимает обратно")
        void bulkAssignsAndClears() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            Task first = task("Первая", NEW);
            Task second = task("Вторая", NEW);

            bulk("""
                    {"taskIds":["%s","%s"],"sprintId":"%s"}""".formatted(first.getId(), second.getId(), sprintId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(2));
            assertThat(sprintIdOf(first)).isEqualTo(sprintId);
            assertThat(sprintIdOf(second)).isEqualTo(sprintId);

            bulk("""
                    {"taskIds":["%s"],"clearSprint":true}""".formatted(first.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(1));
            assertThat(sprintIdOf(first)).isNull();
            assertThat(sprintIdOf(second)).isEqualTo(sprintId);
        }

        @Test
        @DisplayName("удаление спринта возвращает его задачи в бэклог, но не удаляет их")
        void deletingSprintKeepsTasks() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            Task task = taskInSprint("Задача", NEW, sprintId);

            mockMvc.perform(delete("/api/sprints/" + sprintId).header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isNoContent());

            assertThat(sprintRepository.count()).isZero();
            assertThat(taskRepository.findById(task.getId())).isPresent();
            assertThat(sprintIdOf(task)).isNull();
        }
    }

    // ------------------------------------------------------------- список задач и прогресс

    @Nested
    @DisplayName("список задач и прогресс")
    class ListingAndProgress {

        @Test
        @DisplayName("фильтр по спринту и фильтр «бэклог» дают непересекающиеся выборки")
        void filtersBySprintAndBacklog() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            taskInSprint("В спринте", NEW, sprintId);
            task("В бэклоге", NEW);

            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, ownerHeader)
                            .param("sprintId", sprintId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].title").value("В спринте"));

            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, ownerHeader)
                            .param("noSprint", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].title").value("В бэклоге"));
        }

        @Test
        @DisplayName("noSprint сильнее sprintId — как unassigned сильнее assigneeId")
        void backlogFlagWinsOverSprintId() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            taskInSprint("В спринте", NEW, sprintId);
            task("В бэклоге", NEW);

            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, ownerHeader)
                            .param("sprintId", sprintId.toString())
                            .param("noSprint", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].title").value("В бэклоге"));
        }

        @Test
        @DisplayName("прогресс считает закрытыми и DONE, и REJECTED")
        void countsClosedTasks() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            taskInSprint("Сделана", DONE, sprintId);
            taskInSprint("Отклонена", REJECTED, sprintId);
            taskInSprint("В работе", IN_PROGRESS, sprintId);

            mockMvc.perform(get("/api/projects/" + project.getId() + "/sprints")
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].taskCount").value(3))
                    .andExpect(jsonPath("$[0].closedTaskCount").value(2));
        }

        /**
         * Порядок на странице спринтов: активный сверху, завершённые внизу. Он собирается
         * в сервисе, а не в ORDER BY, и поэтому его легко потерять при первой же правке.
         */
        @Test
        @DisplayName("список: активный, затем запланированные, затем завершённые")
        void ordersByLifecycle() throws Exception {
            UUID past = createdSprintId("Прошлый", MONDAY.minusWeeks(4), MONDAY.minusWeeks(2));
            UUID current = createdSprintId("Текущий", MONDAY, FRIDAY);
            createdSprintId("Будущий", FRIDAY.plusDays(3), FRIDAY.plusWeeks(2));

            start(past).andExpect(status().isOk());
            complete(past, null).andExpect(status().isOk());
            start(current).andExpect(status().isOk());

            mockMvc.perform(get("/api/projects/" + project.getId() + "/sprints")
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[0].name").value("Текущий"))
                    .andExpect(jsonPath("$[1].name").value("Будущий"))
                    .andExpect(jsonPath("$[2].name").value("Прошлый"));
        }

        /**
         * Фильтр по спринту обязан сохраняться в представлении (4.7), иначе «мои задачи в
         * текущем спринте» нельзя положить на кнопку — а это первое, чего от спринтов ждут.
         */
        @Test
        @DisplayName("фильтр по спринту сохраняется в представлении")
        void savedViewKeepsSprintFilter() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);

            mockMvc.perform(post("/api/projects/" + project.getId() + "/views")
                            .header(AUTHORIZATION, ownerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Текущий спринт","sprintId":"%s","sort":"NUMBER"}""".formatted(sprintId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sprintId").value(sprintId.toString()))
                    .andExpect(jsonPath("$.noSprint").value(false));

            mockMvc.perform(get("/api/projects/" + project.getId() + "/views")
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].sprintId").value(sprintId.toString()));
        }

        /**
         * Удалённый спринт превращает сохранённый фильтр в «любой спринт» (ON DELETE SET
         * NULL, V31), а не оставляет представление, которое молча показывает пустой список.
         */
        @Test
        @DisplayName("удаление спринта не ломает представление, а расширяет его")
        void savedViewSurvivesSprintDeletion() throws Exception {
            UUID sprintId = createdSprintId("Спринт 1", MONDAY, FRIDAY);
            mockMvc.perform(post("/api/projects/" + project.getId() + "/views")
                            .header(AUTHORIZATION, ownerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Текущий спринт","sprintId":"%s","sort":"NUMBER"}""".formatted(sprintId)))
                    .andExpect(status().isCreated());

            mockMvc.perform(delete("/api/sprints/" + sprintId).header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/projects/" + project.getId() + "/views")
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].sprintId").value(nullValue()));
        }
    }

    // ------------------------------------------------------------------------------ хелперы

    private ResultActions createSprint(String name, LocalDate start, LocalDate end) throws Exception {
        return createSprint(project, name, start, end);
    }

    private ResultActions createSprint(Project target, String name, LocalDate start, LocalDate end) throws Exception {
        return mockMvc.perform(post("/api/projects/" + target.getId() + "/sprints")
                .header(AUTHORIZATION, ownerHeader)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"name":"%s","startDate":"%s","endDate":"%s"}""".formatted(name, start, end)));
    }

    private UUID createdSprintId(String name, LocalDate start, LocalDate end) throws Exception {
        return createdSprintId(project, name, start, end);
    }

    private UUID createdSprintId(Project target, String name, LocalDate start, LocalDate end) throws Exception {
        String body = createSprint(target, name, start, end)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonField(body, "id"));
    }

    private ResultActions start(UUID sprintId) throws Exception {
        return mockMvc.perform(post("/api/sprints/" + sprintId + "/start").header(AUTHORIZATION, ownerHeader));
    }

    private ResultActions complete(UUID sprintId, UUID moveTo) throws Exception {
        String body = moveTo == null
                ? "{\"moveUnfinishedToSprintId\":null}"
                : "{\"moveUnfinishedToSprintId\":\"" + moveTo + "\"}";
        return mockMvc.perform(post("/api/sprints/" + sprintId + "/complete")
                .header(AUTHORIZATION, ownerHeader)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions bulk(String body) throws Exception {
        return mockMvc.perform(patch("/api/projects/" + project.getId() + "/tasks/bulk")
                .header(AUTHORIZATION, memberHeader)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    /** Значение простого строкового/числового поля верхнего уровня — без парсера ради двух мест. */
    private static String jsonField(String json, String field) {
        int at = json.indexOf("\"" + field + "\":");
        String rest = json.substring(at + field.length() + 3);
        if (rest.startsWith("\"")) {
            return rest.substring(1, rest.indexOf('"', 1));
        }
        int end = rest.indexOf(',');
        int brace = rest.indexOf('}');
        return rest.substring(0, end >= 0 && end < brace ? end : brace);
    }

    private UUID sprintIdOf(Task task) {
        return jdbcTemplate.queryForObject("select sprint_id from tasks where id = ?", UUID.class, task.getId());
    }

    private int activityCount(String type) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from project_activity where type = ?", Integer.class, type);
        return count != null ? count : 0;
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
        return saveTask(title, status, null);
    }

    private Task taskInSprint(String title, TaskStatus status, UUID sprintId) {
        return saveTask(title, status, sprintRepository.findById(sprintId).orElseThrow());
    }

    private Task saveTask(String title, TaskStatus status, Sprint sprint) {
        Task task = new Task();
        task.setProject(project);
        task.setTitle(title);
        task.setStatus(status);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setPosition(0);
        task.setCreatedBy(owner);
        task.setSprint(sprint);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        return taskRepository.save(task);
    }
}
