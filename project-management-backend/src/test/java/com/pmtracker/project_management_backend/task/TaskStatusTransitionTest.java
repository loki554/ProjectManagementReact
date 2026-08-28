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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.pmtracker.project_management_backend.task.TaskStatus.DONE;
import static com.pmtracker.project_management_backend.task.TaskStatus.FEEDBACK;
import static com.pmtracker.project_management_backend.task.TaskStatus.IN_PROGRESS;
import static com.pmtracker.project_management_backend.task.TaskStatus.NEW;
import static com.pmtracker.project_management_backend.task.TaskStatus.PAUSED;
import static com.pmtracker.project_management_backend.task.TaskStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Смена статуса задачи и защита от конкурентной правки ({@code TaskStatusConflictException}).
 * <p>
 * 2.3 (TaskReorderIntegrationTest) разбирает вторую половину того же эндпоинта — арифметику
 * позиций внутри колонки. Здесь измерение другое: какие переходы вообще разрешены, что
 * попадает в ленту активности, и когда запрос обязан быть отбит как устаревший. Пересечение
 * ровно одно и намеренное — 2.3 показывает, что доска не меняется при 409, здесь тот же
 * инвариант проверяется на всех статусах сразу.
 * <p>
 * Статус меняется двумя разными путями, и это главное, что здесь фиксируется. Канбан
 * ({@code PATCH /api/tasks/{id}/status}) присылает {@code expectedStatus} — статус, который
 * пользователь видел, когда начинал тащить карточку, — и получает 409, если за это время
 * задачу передвинул кто-то ещё. Форма редактирования ({@code PATCH /api/tasks/{id}})
 * присылает задачу целиком и такой проверки не имеет вовсе. Разница осознанная (в форме
 * пользователь видит поле «статус» и меняет его явно, а не роняет карточку в колонку,
 * которой там уже нет), но нигде, кроме этих тестов, она не записана.
 * <p>
 * Матрица переходов строится из {@code TaskStatus.values()}, а не из литерального списка:
 * новый статус в enum автоматически добавит себе строку и столбец. Если когда-нибудь
 * появится настоящая машина состояний (из DONE нельзя назад в NEW и т.п.), падать начнут
 * ровно те ячейки, которые она запретит, — то есть матрица станет её спецификацией.
 */
class TaskStatusTransitionTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User member;
    private Project project;
    private String memberAuth;
    private String otherMemberAuth;
    private String viewerAuth;

    @BeforeEach
    void createProject() {
        member = createUser("member@example.com");
        User otherMember = createUser("other@example.com");
        User viewer = createUser("viewer@example.com");

        project = new Project();
        project.setName("Status project");
        project.setSlug("status-project");
        project.setCreatedBy(member);
        projectRepository.save(project);

        addMember(member, ProjectRole.OWNER);
        addMember(otherMember, ProjectRole.MEMBER);
        addMember(viewer, ProjectRole.VIEWER);

        memberAuth = "Bearer " + jwtService.generateAccessToken(member);
        otherMemberAuth = "Bearer " + jwtService.generateAccessToken(otherMember);
        viewerAuth = "Bearer " + jwtService.generateAccessToken(viewer);
    }

    // ------------------------------------------------------------- матрица переходов

    @Nested
    @DisplayName("переходы между статусами")
    class Transitions {

        /**
         * Все упорядоченные пары различных статусов. Машины состояний в домене нет —
         * это осознанное решение (доска, а не workflow-движок), и тест его фиксирует:
         * пока запрещённых переходов нет, все 30 ячеек обязаны быть зелёными.
         */
        static Stream<Arguments> allTransitions() {
            return Arrays.stream(TaskStatus.values())
                    .flatMap(from -> Arrays.stream(TaskStatus.values())
                            .filter(to -> to != from)
                            .map(to -> Arguments.of(from, to)));
        }

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("allTransitions")
        @DisplayName("разрешён любой переход")
        void anyTransitionIsAllowed(TaskStatus from, TaskStatus to) throws Exception {
            Task task = topLevel("T", from, 0);

            move(task, to, 0, from).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(to.name()))
                    .andExpect(jsonPath("$.position").value(0));

            assertThat(column(from)).isEmpty();
            assertThat(column(to)).containsExactly("T@0");
            assertThat(statusEvents(task)).containsExactly(from + "→" + to);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TaskStatus.class)
        @DisplayName("перестановка внутри своей колонки не считается сменой статуса и в ленту не идёт")
        void reorderingWithinAColumnIsNotAStatusChange(TaskStatus status) throws Exception {
            topLevel("A", status, 0);
            Task b = topLevel("B", status, 1);

            move(b, status, 0, status).andExpect(status().isOk());

            assertThat(column(status)).containsExactly("B@0", "A@1");
            assertThat(statusEvents(b)).isEmpty();
        }

        @Test
        @DisplayName("цепочка переходов пишет в ленту по событию на каждый шаг, в порядке шагов")
        void everyStepIsRecordedSeparately() throws Exception {
            Task task = topLevel("T", NEW, 0);

            move(task, IN_PROGRESS, 0, NEW).andExpect(status().isOk());
            move(task, FEEDBACK, 0, IN_PROGRESS).andExpect(status().isOk());
            move(task, DONE, 0, FEEDBACK).andExpect(status().isOk());

            assertThat(statusEvents(task)).containsExactly(
                    "NEW→IN_PROGRESS", "IN_PROGRESS→FEEDBACK", "FEEDBACK→DONE");
        }

        @Test
        @DisplayName("возврат из терминального статуса ничем не отличается от любого другого перехода")
        void terminalStatusesAreNotTerminal() throws Exception {
            Task task = topLevel("T", DONE, 0);

            move(task, NEW, 0, DONE).andExpect(status().isOk());
            move(task, REJECTED, 0, NEW).andExpect(status().isOk());
            move(task, IN_PROGRESS, 0, REJECTED).andExpect(status().isOk());

            assertThat(column(IN_PROGRESS)).containsExactly("T@0");
        }
    }

    // ------------------------------------------------- устаревший expectedStatus → 409

    @Nested
    @DisplayName("устаревший expectedStatus")
    class StaleExpectedStatus {

        /**
         * expectedStatus — не «желаемый» и не «предыдущий» статус, а именно тот, который
         * клиент видел перед началом drag. Любое расхождение с БД означает, что задачу
         * успел передвинуть кто-то другой, и повторять его ход вслепую нельзя.
         */
        @ParameterizedTest(name = "задача в PAUSED, клиент думает {0}")
        @EnumSource(value = TaskStatus.class, names = "PAUSED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("любой статус, кроме настоящего, отбивается 409")
        void anyMismatchIsRejected(TaskStatus staleStatus) throws Exception {
            Task task = topLevel("T", PAUSED, 0);

            move(task, DONE, 0, staleStatus)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TASK_STATUS_CONFLICT"));

            assertThat(column(PAUSED)).containsExactly("T@0");
            assertThat(column(DONE)).isEmpty();
            assertThat(statusEvents(task)).isEmpty();
        }

        @Test
        @DisplayName("совпадающий expectedStatus проходит — 409 не срабатывает «на всякий случай»")
        void matchingExpectedStatusPasses() throws Exception {
            Task task = topLevel("T", PAUSED, 0);

            move(task, DONE, 0, PAUSED).andExpect(status().isOk());

            assertThat(column(DONE)).containsExactly("T@0");
        }

        @Test
        @DisplayName("ход второго пользователя по устаревшей доске отбивается, ход первого остаётся")
        void secondUserDraggingAStaleBoardLoses() throws Exception {
            Task task = topLevel("T", NEW, 0);
            topLevel("B", NEW, 1);

            // Первый пользователь перетащил карточку в IN_PROGRESS.
            move(task, IN_PROGRESS, 0, NEW).andExpect(status().isOk());

            // Второй всё ещё видит её в NEW и тащит оттуда в DONE.
            mockMvc.perform(patch("/api/tasks/" + task.getId() + "/status")
                            .header(AUTHORIZATION, otherMemberAuth)
                            .contentType(APPLICATION_JSON)
                            .content(statusBody(DONE, 0, NEW)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TASK_STATUS_CONFLICT"));

            assertThat(column(NEW)).containsExactly("B@0");
            assertThat(column(IN_PROGRESS)).containsExactly("T@0");
            assertThat(column(DONE)).isEmpty();
        }

        @Test
        @DisplayName("перестановка внутри колонки тоже требует актуального expectedStatus")
        void staleStatusIsRejectedEvenForAPureReorder() throws Exception {
            Task a = topLevel("A", NEW, 0);
            topLevel("B", NEW, 1);

            // Клиент думает, что колонка IN_PROGRESS, и переставляет карточку «внутри неё» —
            // на самом деле карточка в NEW, то есть доска перед глазами уже не та.
            move(a, IN_PROGRESS, 0, IN_PROGRESS)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TASK_STATUS_CONFLICT"));

            assertThat(column(NEW)).containsExactly("A@0", "B@1");
        }

        /**
         * Порядок проверок в updateStatus: сначала конфликт, потом граница индекса. Ответ
         * должен объяснять первопричину — доска устарела, — а не следствие («такой позиции
         * в колонке нет»), иначе фронтенд предложит не то действие: перечитать доску вместо
         * «попробуйте ещё раз».
         */
        @Test
        @DisplayName("конфликт важнее невалидной позиции: 409, а не 400")
        void conflictWinsOverAnOutOfRangePosition() throws Exception {
            Task task = topLevel("T", NEW, 0);

            move(task, DONE, 99, IN_PROGRESS)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TASK_STATUS_CONFLICT"));

            assertThat(column(NEW)).containsExactly("T@0");
        }

        /**
         * И наоборот — права важнее конфликта. VIEWER'у незачем сообщать, что доска
         * устарела: перетаскивать он не может в принципе, и «обновите страницу» здесь было
         * бы прямой ложью.
         */
        @Test
        @DisplayName("права важнее конфликта: VIEWER с устаревшим статусом получает 403, а не 409")
        void permissionsAreCheckedBeforeTheConflict() throws Exception {
            Task task = topLevel("T", NEW, 0);

            mockMvc.perform(patch("/api/tasks/" + task.getId() + "/status")
                            .header(AUTHORIZATION, viewerAuth)
                            .contentType(APPLICATION_JSON)
                            .content(statusBody(DONE, 0, IN_PROGRESS)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));

            assertThat(column(NEW)).containsExactly("T@0");
        }

        @Test
        @DisplayName("expectedStatus обязателен — без него запрос не доходит до сервиса")
        void expectedStatusIsRequired() throws Exception {
            Task task = topLevel("T", NEW, 0);

            mockMvc.perform(patch("/api/tasks/" + task.getId() + "/status")
                            .header(AUTHORIZATION, memberAuth)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"status":"DONE","position":0}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

            assertThat(column(NEW)).containsExactly("T@0");
        }

        @Test
        @DisplayName("незнакомый статус — 400 на разборе тела, а не 500")
        void unknownStatusLiteralIsRejected() throws Exception {
            Task task = topLevel("T", NEW, 0);

            mockMvc.perform(patch("/api/tasks/" + task.getId() + "/status")
                            .header(AUTHORIZATION, memberAuth)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"status":"ARCHIVED","position":0,"expectedStatus":"NEW"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

            assertThat(column(NEW)).containsExactly("T@0");
        }

        @Test
        @DisplayName("несуществующая задача — 404, конфликт тут ни при чём")
        void unknownTaskIsNotFound() throws Exception {
            mockMvc.perform(patch("/api/tasks/" + UUID.randomUUID() + "/status")
                            .header(AUTHORIZATION, memberAuth)
                            .contentType(APPLICATION_JSON)
                            .content(statusBody(DONE, 0, NEW)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));
        }
    }

    // ------------------------------------------------ смена статуса из формы редактирования

    @Nested
    @DisplayName("статус из формы редактирования")
    class ViaTheEditForm {

        @Test
        @DisplayName("PATCH /tasks/{id} меняет статус и пишет то же событие, что и drag")
        void editingTheTaskChangesTheStatus() throws Exception {
            Task task = topLevel("T", NEW, 0);

            edit(task, IN_PROGRESS).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

            assertThat(statusEvents(task)).containsExactly("NEW→IN_PROGRESS");
        }

        @Test
        @DisplayName("сохранение формы без изменения статуса в ленту не пишет")
        void savingTheFormWithTheSameStatusRecordsNothing() throws Exception {
            Task task = topLevel("T", NEW, 0);

            edit(task, NEW).andExpect(status().isOk());

            assertThat(statusEvents(task)).isEmpty();
        }

        /**
         * У формы редактирования нет expectedStatus и, соответственно, нет проверки на
         * конкурентную правку — здесь это не потеря, а осознанная разница: пользователь
         * выбирает статус в выпадающем списке явно, «последний сохранивший прав» — ровно
         * то поведение, которого он ждёт от формы.
         */
        @Test
        @DisplayName("оптимистичной блокировки у формы нет: второе сохранение перебивает первое")
        void theEditFormHasNoOptimisticGuard() throws Exception {
            Task task = topLevel("T", NEW, 0);

            edit(task, DONE).andExpect(status().isOk());
            edit(task, REJECTED).andExpect(status().isOk());

            assertThat(column(REJECTED)).containsExactly("T@0");
            assertThat(statusEvents(task)).containsExactly("NEW→DONE", "DONE→REJECTED");
        }

        /**
         * Вторая половина той же разницы, и она уже не бесплатная: форма меняет статус, но
         * позицию не трогает — перенумеровать ей нечего, индекс вставки в запросе не
         * приходит. Задача приезжает в целевую колонку со своей старой позицией, из-за чего
         * позиции в ней могут задвоиться, а в покинутой остаётся дыра. Для доски это
         * допустимое состояние (нумерация и так не гарантирована — см. 2.3), и первое же
         * перетаскивание его выправляет; тест здесь для того, чтобы это осталось известным
         * поведением, а не открытием.
         */
        @Test
        @DisplayName("позиции при этом не пересчитываются — колонки чинит следующий drag")
        void theEditFormLeavesPositionsAlone() throws Exception {
            topLevel("A", NEW, 0);
            Task b = topLevel("B", NEW, 1);
            topLevel("X", DONE, 0);
            Task y = topLevel("Y", DONE, 1);

            edit(b, DONE).andExpect(status().isOk());

            // Дыра на месте B (в NEW осталась только A@0) и задвоенная позиция 1 в DONE.
            assertThat(column(NEW)).containsExactly("A@0");
            assertThat(column(DONE)).containsExactly("X@0", "B@1", "Y@1");

            // Следующее перетаскивание в этой колонке приводит нумерацию в порядок.
            move(y, DONE, 2, DONE).andExpect(status().isOk());
            assertThat(column(DONE)).containsExactly("X@0", "B@1", "Y@2");
        }
    }

    // ------------------------------------------------------------------------- хелперы

    private ResultActions move(Task task, TaskStatus to, int position, TaskStatus expectedStatus) throws Exception {
        return mockMvc.perform(patch("/api/tasks/" + task.getId() + "/status")
                .header(AUTHORIZATION, memberAuth)
                .contentType(APPLICATION_JSON)
                .content(statusBody(to, position, expectedStatus)));
    }

    private String statusBody(TaskStatus to, int position, TaskStatus expectedStatus) {
        return """
                {"status":"%s","position":%d,"expectedStatus":"%s"}"""
                .formatted(to, position, expectedStatus);
    }

    /** Сохранение формы редактирования: тело содержит задачу целиком, expectedStatus в нём нет. */
    private ResultActions edit(Task task, TaskStatus status) throws Exception {
        return mockMvc.perform(patch("/api/tasks/" + task.getId())
                .header(AUTHORIZATION, memberAuth)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"title":"%s","status":"%s","urgency":"MEDIUM"}"""
                        .formatted(task.getTitle(), status)));
    }

    /** Колонка доски как «title@position» — та же оптика, что в 2.3. */
    private List<String> column(TaskStatus status) {
        return jdbcTemplate.queryForList("""
                SELECT title || '@' || "position"
                FROM tasks
                WHERE project_id = ? AND status = ? AND parent_task_id IS NULL
                ORDER BY "position", title
                """, String.class, project.getId(), status.name());
    }

    /**
     * Смены статуса задачи в ленте активности — «СТАРЫЙ→НОВЫЙ» в порядке возникновения.
     * Именно из payload, а не просто по факту наличия события: перепутанные местами old/new
     * не сломали бы ни один запрос, но лента показывала бы обратную стрелку.
     */
    private List<String> statusEvents(Task task) {
        return jdbcTemplate.queryForList("""
                SELECT (payload ->> 'old') || '→' || (payload ->> 'new')
                FROM project_activity
                WHERE task_id = ? AND type = 'task_status_changed'
                ORDER BY created_at
                """, String.class, task.getId());
    }

    private User createUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        user.setLastName("Тестов");
        user.setFirstName(email.substring(0, email.indexOf('@')));
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private void addMember(User user, ProjectRole role) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }

    private Task topLevel(String title, TaskStatus status, int position) {
        Task task = new Task();
        task.setProject(project);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        task.setTitle(title);
        task.setStatus(status);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setPosition(position);
        task.setCreatedBy(member);
        return taskRepository.save(task);
    }
}
