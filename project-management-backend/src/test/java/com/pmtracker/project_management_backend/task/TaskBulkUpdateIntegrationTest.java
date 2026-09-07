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
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.tag.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.pmtracker.project_management_backend.task.TaskStatus.DONE;
import static com.pmtracker.project_management_backend.task.TaskStatus.IN_PROGRESS;
import static com.pmtracker.project_management_backend.task.TaskStatus.NEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Массовые операции над задачами: {@code PATCH /api/projects/{id}/tasks/bulk} (4.6).
 * <p>
 * Проверять это нужно на живой Postgres и по состоянию БД после запроса, а не по ответу:
 * ответ — одно число, а поехать может что угодно из того, чего в запросе не было. Ровно
 * так и ломается массовая правка: не отказом, а тихим побочным эффектом на полусотне задач
 * сразу — снятым заодно исполнителем, разъехавшимися позициями в покинутой колонке,
 * повторным уведомлением тому, у кого ничего не изменилось.
 * <p>
 * Отдельная тема — «всё или ничего». Каждый отказ проверяется дважды: что вернулся нужный
 * код и что при этом не изменилась ни одна задача из списка. Частично применённая массовая
 * правка хуже неприменённой: неприменённую человек повторит, частичную — не заметит.
 */
class TaskBulkUpdateIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private User member;
    private User outsider;
    private Project project;
    private String authHeader;

    @BeforeEach
    void createProject() {
        owner = saveUser("owner@example.com", "Яковлев", "Олег");
        member = saveUser("member@example.com", "Абрамов", "Игорь");
        outsider = saveUser("outsider@example.com", "Чужой", "Человек");

        project = saveProject("Bulk project", "bulk-project");
        join(project, owner, ProjectRole.OWNER);
        join(project, member, ProjectRole.MEMBER);

        authHeader = "Bearer " + jwtService.generateAccessToken(owner);
    }

    // --------------------------------------------------------------------------- статус

    @Nested
    @DisplayName("смена статуса")
    class StatusChange {

        @Test
        @DisplayName("выделенные задачи уезжают в хвост целевой колонки, покинутая перенумеровывается")
        void movesSelectedTasksToTheEndOfTheTargetColumn() throws Exception {
            Task a = task("A").status(NEW).position(0).save();
            task("B").status(NEW).position(1).save();
            Task c = task("C").status(NEW).position(2).save();
            task("D").status(IN_PROGRESS).position(0).save();

            bulk("""
                    {"taskIds":["%s","%s"],"status":"IN_PROGRESS"}""".formatted(a.getId(), c.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(2));

            // Оставшаяся в NEW задача съезжает на нулевую позицию — дыр после переезда быть
            // не должно, ровно как после перетаскивания одной карточки.
            assertThat(column(NEW)).containsExactly("B@0");
            // В хвост, а не в начало, и между собой — по номеру задачи (A создана раньше C).
            assertThat(column(IN_PROGRESS)).containsExactly("D@0", "A@1", "C@2");
        }

        @Test
        @DisplayName("задача, уже стоящая в целевом статусе, не переезжает и не считается изменённой")
        void tasksAlreadyInTheTargetStatusAreLeftAlone() throws Exception {
            Task a = task("A").status(NEW).position(0).save();
            Task b = task("B").status(DONE).position(0).save();

            bulk("""
                    {"taskIds":["%s","%s"],"status":"DONE"}""".formatted(a.getId(), b.getId()))
                    .andExpect(status().isOk())
                    // Две задачи в запросе, изменилась одна — счётчик про изменения, а не
                    // про размер выделения.
                    .andExpect(jsonPath("$.updated").value(1));

            // B осталась там, где была, и не уехала в хвост за собственной колонкой.
            assertThat(column(DONE)).containsExactly("B@0", "A@1");
        }

        @Test
        @DisplayName("подзадачи считают позиции в колонке своего родителя, а не среди top-level")
        void subtasksAreRestackedWithinTheirOwnParentColumn() throws Exception {
            Task parent = task("P").status(NEW).position(0).save();
            Task sub1 = task("S1").parent(parent).status(NEW).position(0).save();
            task("S2").parent(parent).status(IN_PROGRESS).position(0).save();
            task("Top").status(IN_PROGRESS).position(0).save();

            bulk("""
                    {"taskIds":["%s"],"status":"IN_PROGRESS"}""".formatted(sub1.getId()))
                    .andExpect(status().isOk());

            // Подзадача встала в хвост колонки IN_PROGRESS своего родителя и никак не
            // затронула одноимённую колонку доски.
            assertThat(subtaskColumn(parent, IN_PROGRESS)).containsExactly("S2@0", "S1@1");
            assertThat(column(IN_PROGRESS)).containsExactly("Top@0");
        }

        @Test
        @DisplayName("задачи из разных колонок съезжаются в одну, каждая покинутая перенумеровывается")
        void tasksFromDifferentColumnsMergeIntoOne() throws Exception {
            Task a = task("A").status(NEW).position(0).save();
            task("B").status(NEW).position(1).save();
            Task c = task("C").status(IN_PROGRESS).position(0).save();
            task("D").status(IN_PROGRESS).position(1).save();

            bulk("""
                    {"taskIds":["%s","%s"],"status":"DONE"}""".formatted(a.getId(), c.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(2));

            assertThat(column(NEW)).containsExactly("B@0");
            assertThat(column(IN_PROGRESS)).containsExactly("D@0");
            assertThat(column(DONE)).containsExactly("A@0", "C@1");
        }
    }

    // ------------------------------------------------------------- остальные поля и «снять»

    @Nested
    @DisplayName("исполнитель, тэг и срок")
    class OtherFields {

        @Test
        @DisplayName("исполнитель, тэг и срок меняются одним запросом")
        void changesEveryFieldAtOnce() throws Exception {
            Tag tag = tag("важное");
            Task a = task("A").save();
            Task b = task("B").save();
            Instant due = Instant.parse("2026-12-31T10:00:00Z");

            bulk("""
                    {"taskIds":["%s","%s"],"status":"IN_PROGRESS","assigneeId":"%s","tagId":"%s","dueDate":"%s"}"""
                    .formatted(a.getId(), b.getId(), member.getId(), tag.getId(), due))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(2));

            for (Task task : List.of(a, b)) {
                Task saved = reload(task);
                assertThat(saved.getStatus()).isEqualTo(IN_PROGRESS);
                assertThat(saved.getAssignee().getId()).isEqualTo(member.getId());
                assertThat(saved.getTag().getId()).isEqualTo(tag.getId());
                assertThat(saved.getDueDate()).isEqualTo(due);
            }
        }

        @Test
        @DisplayName("флаги clear* снимают исполнителя, тэг и срок")
        void clearFlagsEmptyTheFields() throws Exception {
            Tag tag = tag("важное");
            Task a = task("A").assignee(member).tag(tag).dueDate(Instant.parse("2026-12-31T10:00:00Z")).save();

            bulk("""
                    {"taskIds":["%s"],"clearAssignee":true,"clearTag":true,"clearDueDate":true}"""
                    .formatted(a.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(1));

            Task saved = reload(a);
            assertThat(saved.getAssignee()).isNull();
            assertThat(saved.getTag()).isNull();
            assertThat(saved.getDueDate()).isNull();
        }

        /**
         * Самая дорогая ошибка этого эндпоинта: «сменить статус» не должно ничего снимать.
         * Присланный null и неприсланное поле на проводе неотличимы, поэтому очистку и
         * пришлось развести на отдельные флаги — тест сторожит именно эту границу.
         */
        @Test
        @DisplayName("null в поле — не «очистить», а «не трогать»")
        void nullMeansUntouchedRatherThanCleared() throws Exception {
            Tag tag = tag("важное");
            Instant due = Instant.parse("2026-12-31T10:00:00Z");
            Task a = task("A").assignee(member).tag(tag).dueDate(due).save();

            bulk("""
                    {"taskIds":["%s"],"status":"DONE","assigneeId":null,"tagId":null,"dueDate":null}"""
                    .formatted(a.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(1));

            Task saved = reload(a);
            assertThat(saved.getStatus()).isEqualTo(DONE);
            assertThat(saved.getAssignee().getId()).isEqualTo(member.getId());
            assertThat(saved.getTag().getId()).isEqualTo(tag.getId());
            assertThat(saved.getDueDate()).isEqualTo(due);
        }

        @Test
        @DisplayName("флаг очистки сильнее присланного значения")
        void clearFlagWinsOverTheValue() throws Exception {
            Task a = task("A").assignee(member).save();

            bulk("""
                    {"taskIds":["%s"],"assigneeId":"%s","clearAssignee":true}"""
                    .formatted(a.getId(), member.getId()))
                    .andExpect(status().isOk());

            assertThat(reload(a).getAssignee()).isNull();
        }

        @Test
        @DisplayName("дубликат id в выделении применяется один раз")
        void duplicateIdsAreCollapsed() throws Exception {
            Task a = task("A").save();

            bulk("""
                    {"taskIds":["%s","%s"],"status":"DONE"}""".formatted(a.getId(), a.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(1));

            assertThat(activityTypes(a)).containsExactly("task_status_changed");
        }
    }

    // ------------------------------------------------------------------- лента и уведомления

    @Nested
    @DisplayName("лента активности и уведомления")
    class ActivityAndNotifications {

        /**
         * По событию на задачу и на поле — теми же типами, что и одиночная правка. Событие
         * «изменено массово» одной строкой на проект выглядело бы аккуратнее в ленте
         * проекта и полностью исчезло бы из ленты самой задачи.
         */
        @Test
        @DisplayName("каждое реально изменившееся поле каждой задачи — своё событие ленты")
        void writesOneActivityEventPerChangedField() throws Exception {
            Task a = task("A").save();
            Task b = task("B").save();

            bulk("""
                    {"taskIds":["%s","%s"],"status":"DONE","assigneeId":"%s"}"""
                    .formatted(a.getId(), b.getId(), member.getId()))
                    .andExpect(status().isOk());

            assertThat(activityTypes(a)).containsExactlyInAnyOrder("task_status_changed", "task_assignee_changed");
            assertThat(activityTypes(b)).containsExactlyInAnyOrder("task_status_changed", "task_assignee_changed");
        }

        @Test
        @DisplayName("поле, у которого значение и так стояло, событием не считается")
        void unchangedFieldsProduceNoEvents() throws Exception {
            Task a = task("A").assignee(member).save();

            bulk("""
                    {"taskIds":["%s"],"assigneeId":"%s"}""".formatted(a.getId(), member.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(0));

            assertThat(activityTypes(a)).isEmpty();
            assertThat(notificationCount(member, "task_assigned")).isZero();
        }

        @Test
        @DisplayName("назначенному приходит уведомление по каждой задаче")
        void notifiesTheNewAssigneeAboutEveryTask() throws Exception {
            Task a = task("A").save();
            Task b = task("B").save();

            bulk("""
                    {"taskIds":["%s","%s"],"assigneeId":"%s"}"""
                    .formatted(a.getId(), b.getId(), member.getId()))
                    .andExpect(status().isOk());

            // Именно два, а не одно на всю пачку: уведомление ведёт на задачу, и сводке
            // вести некуда (см. TaskService.bulkUpdate).
            assertThat(notificationCount(member, "task_assigned")).isEqualTo(2);
        }

        @Test
        @DisplayName("назначить пачку на себя — не событие для уведомлений")
        void assigningToYourselfNotifiesNobody() throws Exception {
            Task a = task("A").save();

            bulk("""
                    {"taskIds":["%s"],"assigneeId":"%s"}""".formatted(a.getId(), owner.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(1));

            assertThat(notificationCount(owner, "task_assigned")).isZero();
        }

        /**
         * «Скоро истекает»/«просрочена» пересчитывает планировщик, а массовая правка обязана
         * убрать те, что перестали быть правдой: иначе в колокольчике останется напоминание
         * о сроке, которого у задачи больше нет.
         */
        @Test
        @DisplayName("снятый срок убирает прежние напоминания о дедлайне")
        void clearingTheDueDateDropsStaleDueDateAlerts() throws Exception {
            Task a = task("A").assignee(member).dueDate(Instant.now().plus(1, ChronoUnit.DAYS)).save();
            insertDueSoonAlert(a, member);

            bulk("""
                    {"taskIds":["%s"],"clearDueDate":true}""".formatted(a.getId()))
                    .andExpect(status().isOk());

            assertThat(notificationCount(member, "task_due_soon")).isZero();
        }
    }

    // -------------------------------------------------------------------------- отказы

    @Nested
    @DisplayName("отказы")
    class Rejections {

        @Test
        @DisplayName("ни одного поля к правке — 400, задачи не тронуты")
        void rejectsARequestWithNothingToChange() throws Exception {
            Task a = task("A").save();

            bulk("""
                    {"taskIds":["%s"]}""".formatted(a.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("BULK_UPDATE_NO_CHANGES"));

            assertThat(reload(a).getStatus()).isEqualTo(NEW);
        }

        @Test
        @DisplayName("пустой список задач отсекается валидацией DTO")
        void rejectsAnEmptySelection() throws Exception {
            bulk("""
                    {"taskIds":[],"status":"DONE"}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("выделение больше потолка отсекается валидацией DTO")
        void rejectsASelectionOverTheCap() throws Exception {
            String ids = IntStream.range(0, 201)
                    .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                    .collect(Collectors.joining(","));

            bulk("""
                    {"taskIds":[%s],"status":"DONE"}""".formatted(ids))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }

        /**
         * Всё или ничего. Пропускать неизвестные id молча — значит показывать «обновлено 1»
         * там, где человек просил две, и не сказать, какая из них не поехала.
         */
        @Test
        @DisplayName("задача из чужого проекта — 404, своя задача из того же запроса не меняется")
        void rejectsTheWholeRequestIfAnIdBelongsToAnotherProject() throws Exception {
            Task mine = task("A").save();
            Project other = saveProject("Other project", "other-project");
            join(other, owner, ProjectRole.OWNER);
            Task foreign = saveTask(other, "Чужая", NEW, 0, null);

            bulk("""
                    {"taskIds":["%s","%s"],"status":"DONE"}""".formatted(mine.getId(), foreign.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));

            assertThat(reload(mine).getStatus()).isEqualTo(NEW);
        }

        @Test
        @DisplayName("задача, уехавшая в корзину, читается как несуществующая — 404 на весь запрос")
        void rejectsTheWholeRequestIfATaskIsInTheTrash() throws Exception {
            Task mine = task("A").save();
            Task trashed = task("B").save();
            // Через API, а не softDelete() напрямую: @Modifying-запрос требует транзакции,
            // а тесты здесь принципиально нетранзакционные (см. IntegrationTest).
            mockMvc.perform(delete("/api/tasks/" + trashed.getId()).header(AUTHORIZATION, authHeader))
                    .andExpect(status().isNoContent());

            bulk("""
                    {"taskIds":["%s","%s"],"status":"DONE"}""".formatted(mine.getId(), trashed.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));

            assertThat(reload(mine).getStatus()).isEqualTo(NEW);
        }

        @Test
        @DisplayName("исполнитель не из проекта — 400, ни одна задача не тронута")
        void rejectsAnAssigneeWhoIsNotAProjectMember() throws Exception {
            Task a = task("A").save();

            bulk("""
                    {"taskIds":["%s"],"status":"DONE","assigneeId":"%s"}"""
                    .formatted(a.getId(), outsider.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("ASSIGNEE_NOT_PROJECT_MEMBER"));

            // Статус в том же запросе тоже не применился: правка транзакционна целиком.
            assertThat(reload(a).getStatus()).isEqualTo(NEW);
        }

        @Test
        @DisplayName("тэг из чужого проекта — 400, задачи не тронуты")
        void rejectsATagFromAnotherProject() throws Exception {
            Task a = task("A").save();
            Project other = saveProject("Other project", "other-project");
            Tag foreignTag = tag(other, "чужой");

            bulk("""
                    {"taskIds":["%s"],"status":"DONE","tagId":"%s"}"""
                    .formatted(a.getId(), foreignTag.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("TAG_PROJECT_MISMATCH"));

            assertThat(reload(a).getStatus()).isEqualTo(NEW);
        }

        @Test
        @DisplayName("VIEWER не может править массово — 403")
        void rejectsViewers() throws Exception {
            User viewer = saveUser("viewer@example.com", "Смотров", "Виктор");
            join(project, viewer, ProjectRole.VIEWER);
            Task a = task("A").save();

            mockMvc.perform(patch("/api/projects/" + project.getId() + "/tasks/bulk")
                            .header(AUTHORIZATION, "Bearer " + jwtService.generateAccessToken(viewer))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"taskIds":["%s"],"status":"DONE"}""".formatted(a.getId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));

            assertThat(reload(a).getStatus()).isEqualTo(NEW);
        }

        @Test
        @DisplayName("посторонний не видит проекта вовсе — 403")
        void rejectsNonMembers() throws Exception {
            Task a = task("A").save();

            mockMvc.perform(patch("/api/projects/" + project.getId() + "/tasks/bulk")
                            .header(AUTHORIZATION, "Bearer " + jwtService.generateAccessToken(outsider))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"taskIds":["%s"],"status":"DONE"}""".formatted(a.getId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("NOT_A_PROJECT_MEMBER"));

            assertThat(reload(a).getStatus()).isEqualTo(NEW);
        }
    }

    // ------------------------------------------------------------------------- хелперы

    private ResultActions bulk(String body) throws Exception {
        return mockMvc.perform(patch("/api/projects/" + project.getId() + "/tasks/bulk")
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    /**
     * Колонка доски — «title@position» в порядке позиций, прямо из БД: ответ эндпоинта
     * не содержит задач вовсе, а разъехаться может любая. {@code IS NOT DISTINCT FROM} —
     * потому что у top-level задач parent_task_id равен NULL (см. TaskReorderIntegrationTest).
     */
    private List<String> column(TaskStatus status, UUID parentTaskId) {
        return jdbcTemplate.queryForList("""
                SELECT title || '@' || "position"
                FROM tasks
                WHERE project_id = ? AND status = ? AND parent_task_id IS NOT DISTINCT FROM ?
                ORDER BY "position", title
                """, String.class, project.getId(), status.name(), parentTaskId);
    }

    private List<String> column(TaskStatus status) {
        return column(status, null);
    }

    private List<String> subtaskColumn(Task parent, TaskStatus status) {
        return column(status, parent.getId());
    }

    private List<String> activityTypes(Task task) {
        return jdbcTemplate.queryForList(
                "SELECT type FROM project_activity WHERE task_id = ? ORDER BY type",
                String.class, task.getId());
    }

    private long notificationCount(User recipient, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                Long.class, recipient.getId(), type);
    }

    /** Напоминание о дедлайне так, как его создал бы NotificationScheduler. */
    private void insertDueSoonAlert(Task task, User recipient) {
        jdbcTemplate.update("""
                INSERT INTO notifications (recipient_id, type, task_id, payload)
                VALUES (?, 'task_due_soon', ?, '{}'::jsonb)
                """, recipient.getId(), task.getId());
    }

    private Task reload(Task task) {
        return taskRepository.findById(task.getId()).orElseThrow();
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

    private Task saveTask(Project target, String title, TaskStatus status, int position, Task parent) {
        Task task = new Task();
        task.setProject(target);
        task.setParentTask(parent);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(target.getId()));
        task.setTitle(title);
        task.setStatus(status);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setPosition(position);
        task.setCreatedBy(owner);
        return taskRepository.save(task);
    }

    private TaskBuilder task(String title) {
        return new TaskBuilder(title);
    }

    /** Задачи заводятся напрямую: тестам нужны конкретные комбинации пустых полей и позиций. */
    private final class TaskBuilder {
        private final Task task = new Task();

        private TaskBuilder(String title) {
            task.setProject(project);
            task.setTitle(title);
            task.setStatus(NEW);
            task.setUrgency(TaskUrgency.MEDIUM);
            task.setCreatedBy(owner);
        }

        TaskBuilder status(TaskStatus status) {
            task.setStatus(status);
            return this;
        }

        TaskBuilder position(int position) {
            task.setPosition(position);
            return this;
        }

        TaskBuilder assignee(User user) {
            task.setAssignee(user);
            return this;
        }

        TaskBuilder tag(Tag tag) {
            task.setTag(tag);
            return this;
        }

        TaskBuilder dueDate(Instant dueDate) {
            task.setDueDate(dueDate);
            return this;
        }

        TaskBuilder parent(Task parent) {
            task.setParentTask(parent);
            return this;
        }

        Task save() {
            task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
            return taskRepository.save(task);
        }
    }
}
