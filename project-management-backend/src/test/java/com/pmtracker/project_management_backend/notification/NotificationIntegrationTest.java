package com.pmtracker.project_management_backend.notification;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.pmtracker.project_management_backend.notification.NotificationService.TYPE_TASK_ASSIGNED;
import static com.pmtracker.project_management_backend.notification.NotificationService.TYPE_TASK_COMMENT;
import static com.pmtracker.project_management_backend.notification.NotificationService.TYPE_TASK_DUE_SOON;
import static com.pmtracker.project_management_backend.notification.NotificationService.TYPE_TASK_OVERDUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Уведомления: дедупликация сканера дедлайнов и гашение устаревших алертов.
 * <p>
 * Ошибки в этом коде не выглядят как ошибки. Сломанная дедупликация не роняет ни одного
 * запроса — она просто присылает пользователю одно и то же уведомление каждые пятнадцать
 * минут, пока он не выключит колокольчик совсем. Забытый {@code clearDueDateAlerts} так же
 * молча оставляет «задача просрочена» на задаче, у которой срок уже сдвинули. Ни то, ни
 * другое не видно ни в логах, ни в ответах API — только в чужом почтовом ящике неделю спустя.
 * <p>
 * Сканер здесь вызывается вручную ({@code checkDueDates()}), а не ждётся по расписанию: тик
 * раз в 15 минут проверить нечем, а главное — проверять надо не «сработало ли расписание»
 * (это Spring), а что именно создаёт один прогон и что делает второй прогон подряд. Чтобы
 * фоновый тик не вмешался в середине теста, в профиле {@code test} его старт отодвинут на
 * сутки (см. application-test.yml); ради этого период сканирования переехал из констант в
 * свойства.
 * <p>
 * Сроки задач берутся относительно {@code Instant.now()} и обрезаны до секунд: сравнение
 * «дедлайн не менялся» в {@code TaskService.update} идёт по точному равенству
 * {@code Instant}, а Postgres хранит микросекунды — необрезанное наносекундное время
 * вернулось бы из базы другим, и тест про «сохранение формы не гасит алерты» стал бы
 * проверять не то, что написано в его названии.
 */
class NotificationIntegrationTest extends IntegrationTest {

    /** Совпадает с NotificationScheduler.DUE_SOON_WINDOW — окно «скоро истекает». */
    private static final Duration DUE_SOON_WINDOW = Duration.ofDays(3);

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private NotificationScheduler notificationScheduler;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User author;
    private User assignee;
    private User bystander;
    private Project project;
    private String authorAuth;
    private String assigneeAuth;

    @BeforeEach
    void createProject() {
        author = createUser("author@example.com", "Автор");
        assignee = createUser("assignee@example.com", "Исполнитель");
        bystander = createUser("bystander@example.com", "Посторонний");

        project = new Project();
        project.setName("Notification project");
        project.setSlug("notification-project");
        project.setCreatedBy(author);
        projectRepository.save(project);

        addMember(author, ProjectRole.OWNER);
        addMember(assignee, ProjectRole.MEMBER);
        addMember(bystander, ProjectRole.MEMBER);

        authorAuth = "Bearer " + jwtService.generateAccessToken(author);
        assigneeAuth = "Bearer " + jwtService.generateAccessToken(assignee);
    }

    // --------------------------------------------------- сканер дедлайнов и дедупликация

    @Nested
    @DisplayName("сканер дедлайнов")
    class DueDateScan {

        @Test
        @DisplayName("просроченная задача даёт task_overdue исполнителю")
        void overdueTaskNotifiesTheAssignee() throws Exception {
            Task task = task("Overdue", assignee, hoursFromNow(-1), TaskStatus.IN_PROGRESS);

            scan();

            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_OVERDUE + ":Overdue");
            assertThat(notificationsOf(author)).isEmpty();
            assertThat(payloadOf(task, TYPE_TASK_OVERDUE, "dueDate")).isEqualTo(task.getDueDate().toString());
        }

        @Test
        @DisplayName("приближающийся дедлайн даёт task_due_soon")
        void upcomingDeadlineNotifiesTheAssignee() throws Exception {
            task("Soon", assignee, daysFromNow(2), TaskStatus.NEW);

            scan();

            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_DUE_SOON + ":Soon");
        }

        /**
         * Сердце пункта: сканер запускается каждые 15 минут и своей памяти о том, кого уже
         * предупреждал, не имеет — единственное, что удерживает его от рассылки одного и того
         * же уведомления четыре раза в час, это проверка existsBy(recipient, task, type).
         */
        @Test
        @DisplayName("повторные прогоны не плодят дубликаты")
        void repeatedScansAreIdempotent() throws Exception {
            task("Overdue", assignee, hoursFromNow(-1), TaskStatus.IN_PROGRESS);
            task("Soon", assignee, daysFromNow(2), TaskStatus.NEW);

            scan();
            scan();
            scan();

            assertThat(notificationsOf(assignee)).containsExactlyInAnyOrder(
                    TYPE_TASK_OVERDUE + ":Overdue", TYPE_TASK_DUE_SOON + ":Soon");
        }

        /**
         * Дедупликация идёт по тройке (получатель, задача, тип), а не по одному получателю:
         * две горящие задачи — два уведомления, иначе про вторую пользователь не узнает.
         */
        @Test
        @DisplayName("две разные задачи — два уведомления одному человеку")
        void deduplicationIsPerTaskNotPerUser() throws Exception {
            task("First", assignee, hoursFromNow(-1), TaskStatus.NEW);
            task("Second", assignee, hoursFromNow(-2), TaskStatus.NEW);

            scan();
            scan();

            assertThat(notificationsOf(assignee)).containsExactlyInAnyOrder(
                    TYPE_TASK_OVERDUE + ":First", TYPE_TASK_OVERDUE + ":Second");
        }

        /**
         * Типы дедуплицируются независимо, и это не оплошность: «срок подходит» и «срок
         * прошёл» — разные сообщения, и второе обязано прийти, даже если первое уже
         * приходило. Итог — два уведомления на одну задачу за её жизнь, но не больше.
         */
        @Test
        @DisplayName("после due_soon приходит ещё и overdue — но каждый тип по одному разу")
        void overdueFollowsDueSoonExactlyOnce() throws Exception {
            Task task = task("Soon", assignee, hoursFromNow(1), TaskStatus.NEW);

            scan();
            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_DUE_SOON + ":Soon");

            // Срок наступил, пока задача была открыта.
            shiftDueDate(task, hoursFromNow(-1));
            scan();
            scan();

            assertThat(notificationsOf(assignee)).containsExactlyInAnyOrder(
                    TYPE_TASK_DUE_SOON + ":Soon", TYPE_TASK_OVERDUE + ":Soon");
        }

        @Test
        @DisplayName("задача без исполнителя не уведомляет никого, даже просроченная")
        void unassignedTaskNotifiesNobody() throws Exception {
            task("Nobody's", null, hoursFromNow(-5), TaskStatus.NEW);

            scan();

            assertThat(allNotifications()).isEmpty();
        }

        @Test
        @DisplayName("задача без дедлайна кандидатом не является")
        void taskWithoutADueDateIsNotACandidate() throws Exception {
            task("No deadline", assignee, null, TaskStatus.NEW);

            scan();

            assertThat(allNotifications()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TaskStatus.class, names = {"DONE", "REJECTED"})
        @DisplayName("закрытая задача не напоминает о себе, как бы ни была просрочена")
        void closedTasksAreSkipped(TaskStatus closedStatus) throws Exception {
            task("Closed", assignee, hoursFromNow(-100), closedStatus);

            scan();

            assertThat(allNotifications()).isEmpty();
        }

        @Test
        @DisplayName("дедлайн за пределами окна «скоро» ещё не повод беспокоить")
        void deadlinesBeyondTheWindowAreIgnored() throws Exception {
            task("Later", assignee, Instant.now().plus(DUE_SOON_WINDOW).plus(Duration.ofHours(1)), TaskStatus.NEW);

            scan();

            assertThat(allNotifications()).isEmpty();
        }

        @Test
        @DisplayName("дедлайн внутри окна — уже повод")
        void deadlinesJustInsideTheWindowAreCaught() throws Exception {
            task("Edge", assignee, Instant.now().plus(DUE_SOON_WINDOW).minus(Duration.ofHours(1)), TaskStatus.NEW);

            scan();

            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_DUE_SOON + ":Edge");
        }

        @Test
        @DisplayName("у системных уведомлений нет автора — их прислало приложение, а не человек")
        void systemAlertsHaveNoActor() throws Exception {
            task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);

            scan();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notifications WHERE actor_id IS NOT NULL", Integer.class)).isZero();
        }
    }

    // ------------------------------------------------------- гашение устаревших алертов

    @Nested
    @DisplayName("гашение алертов при изменении задачи")
    class ClearingDueDateAlerts {

        @Test
        @DisplayName("сдвиг дедлайна убирает алерт, а следующий скан ставит новый по новому сроку")
        void movingTheDueDateClearsAndRearms() throws Exception {
            Task task = task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);
            scan();
            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_OVERDUE + ":Overdue");

            // Срок перенесли на послезавтра — «просрочена» стало неправдой.
            editTask(task, TaskStatus.NEW, assignee, daysFromNow(2)).andExpect(status().isOk());
            assertThat(notificationsOf(assignee)).isEmpty();

            scan();
            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_DUE_SOON + ":Overdue");
        }

        /**
         * Обратная сторона гашения и единственное, что удерживает его от превращения в
         * генератор спама: если бы алерты чистились на каждое сохранение формы, дедупликация
         * обнулялась бы вместе с ними, и ближайший скан присылал бы «просрочена» заново —
         * столько раз, сколько задачу открывали и сохраняли.
         */
        @Test
        @DisplayName("сохранение формы без изменений алерт не трогает")
        void savingTheFormUnchangedKeepsTheAlert() throws Exception {
            Task task = task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);
            scan();

            editTask(task, TaskStatus.NEW, assignee, task.getDueDate()).andExpect(status().isOk());

            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_OVERDUE + ":Overdue");
            scan();
            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_OVERDUE + ":Overdue");
        }

        @Test
        @DisplayName("смена исполнителя убирает алерт прежнего — задача больше не его")
        void reassigningClearsThePreviousAssigneesAlert() throws Exception {
            Task task = task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);
            scan();
            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_OVERDUE + ":Overdue");

            editTask(task, TaskStatus.NEW, bystander, task.getDueDate()).andExpect(status().isOk());

            assertThat(notificationsOf(assignee)).isEmpty();
            // Новому исполнителю пришло только «на вас назначена задача»; про срок ему
            // расскажет ближайший скан.
            assertThat(notificationsOf(bystander)).containsExactly(TYPE_TASK_ASSIGNED + ":Overdue");

            scan();
            assertThat(notificationsOf(bystander)).containsExactlyInAnyOrder(
                    TYPE_TASK_ASSIGNED + ":Overdue", TYPE_TASK_OVERDUE + ":Overdue");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TaskStatus.class, names = {"DONE", "REJECTED"})
        @DisplayName("закрытие задачи через форму убирает алерты")
        void closingTheTaskViaTheFormClearsAlerts(TaskStatus closedStatus) throws Exception {
            Task task = task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);
            scan();

            editTask(task, closedStatus, assignee, task.getDueDate()).andExpect(status().isOk());

            assertThat(notificationsOf(assignee)).isEmpty();
            scan();
            assertThat(notificationsOf(assignee)).isEmpty();
        }

        /**
         * Второй путь к тому же состоянию: карточку не редактируют, а перетаскивают в
         * колонку «Готово». Проверка в updateStatus отдельная от той, что в update, — то
         * есть починка одной из них другую не чинит.
         */
        @Test
        @DisplayName("перетаскивание в DONE на канбане тоже убирает алерты")
        void draggingToDoneClearsAlerts() throws Exception {
            Task task = task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);
            scan();

            mockMvc.perform(patch("/api/tasks/" + task.getId() + "/status")
                            .header(AUTHORIZATION, authorAuth)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"status":"DONE","position":0,"expectedStatus":"NEW"}"""))
                    .andExpect(status().isOk());

            assertThat(notificationsOf(assignee)).isEmpty();
        }

        @Test
        @DisplayName("перетаскивание между рабочими колонками алерты сохраняет")
        void draggingBetweenOpenColumnsKeepsAlerts() throws Exception {
            Task task = task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);
            scan();

            mockMvc.perform(patch("/api/tasks/" + task.getId() + "/status")
                            .header(AUTHORIZATION, authorAuth)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"status":"IN_PROGRESS","position":0,"expectedStatus":"NEW"}"""))
                    .andExpect(status().isOk());

            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_OVERDUE + ":Overdue");
        }

        /**
         * Гашение сделано bulk-запросом по task_id без разбора типов по одному — легко
         * промахнуться и снести всё, что висит на задаче. Уведомление о назначении и
         * комментарии от срока не зависят и остаться обязаны.
         */
        @Test
        @DisplayName("гасятся только алерты срока, событийные уведомления остаются")
        void onlyDueDateAlertsAreCleared() throws Exception {
            Task task = task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);
            comment(task, authorAuth, "Что там со сроком?").andExpect(status().isCreated());
            scan();
            assertThat(notificationsOf(assignee)).containsExactlyInAnyOrder(
                    TYPE_TASK_COMMENT + ":Overdue", TYPE_TASK_OVERDUE + ":Overdue");

            editTask(task, TaskStatus.NEW, assignee, daysFromNow(2)).andExpect(status().isOk());

            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_COMMENT + ":Overdue");
        }

        @Test
        @DisplayName("гасятся алерты только той задачи, которую правили")
        void alertsOfOtherTasksSurvive() throws Exception {
            Task edited = task("Edited", assignee, hoursFromNow(-1), TaskStatus.NEW);
            task("Untouched", assignee, hoursFromNow(-2), TaskStatus.NEW);
            scan();

            editTask(edited, TaskStatus.NEW, assignee, daysFromNow(2)).andExpect(status().isOk());

            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_OVERDUE + ":Untouched");
        }
    }

    // ---------------------------------------------------------- событийные уведомления

    @Nested
    @DisplayName("назначение и комментарии")
    class EventNotifications {

        @Test
        @DisplayName("назначение на себя не уведомляет — человек и так знает")
        void selfAssignmentIsSilent() throws Exception {
            Task task = task("T", null, null, TaskStatus.NEW);

            editTask(task, TaskStatus.NEW, author, null).andExpect(status().isOk());

            assertThat(allNotifications()).isEmpty();
        }

        @Test
        @DisplayName("снятие исполнителя не уведомляет никого и не падает")
        void unassigningIsSilent() throws Exception {
            Task task = task("T", assignee, null, TaskStatus.NEW);

            editTask(task, TaskStatus.NEW, null, null).andExpect(status().isOk());

            assertThat(allNotifications()).isEmpty();
        }

        @Test
        @DisplayName("комментарий уведомляет постановщика и исполнителя, кроме самого автора")
        void commentNotifiesBothSidesExceptTheAuthor() throws Exception {
            Task task = task("T", assignee, null, TaskStatus.NEW);

            comment(task, authorAuth, "Как продвигается?").andExpect(status().isCreated());

            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_COMMENT + ":T");
            assertThat(notificationsOf(author)).isEmpty();
        }

        @Test
        @DisplayName("постановщик и исполнитель — один человек: одно уведомление, не два")
        void oneRecipientGetsOneNotification() throws Exception {
            Task task = task("T", author, null, TaskStatus.NEW);

            comment(task, assigneeAuth, "Комментарий от третьего лица").andExpect(status().isCreated());

            assertThat(notificationsOf(author)).containsExactly(TYPE_TASK_COMMENT + ":T");
        }

        /**
         * Регрессия из 2.2: получатели собирались через {@code List.of(createdBy, assignee)},
         * а {@code List.of} бросает NPE на null-элементе — то есть любой комментарий к
         * неназначенной задаче (состояние по умолчанию сразу после создания) отвечал 500.
         */
        @Test
        @DisplayName("комментарий к неназначенной задаче доходит до постановщика")
        void commentOnAnUnassignedTaskReachesTheCreator() throws Exception {
            Task task = task("T", null, null, TaskStatus.NEW);

            comment(task, assigneeAuth, "Возьму себе?").andExpect(status().isCreated());

            assertThat(notificationsOf(author)).containsExactly(TYPE_TASK_COMMENT + ":T");
        }

        @Test
        @DisplayName("автор комментария — сам постановщик неназначенной задачи: тишина")
        void commentingOnYourOwnUnassignedTaskNotifiesNobody() throws Exception {
            Task task = task("T", null, null, TaskStatus.NEW);

            comment(task, authorAuth, "Заметка себе").andExpect(status().isCreated());

            assertThat(allNotifications()).isEmpty();
        }

        @Test
        @DisplayName("длинный комментарий в уведомлении обрезается, короткий — нет")
        void longCommentsAreExcerpted() throws Exception {
            Task longOne = task("Long", assignee, null, TaskStatus.NEW);
            Task shortOne = task("Short", assignee, null, TaskStatus.NEW);
            String body = "я".repeat(200);

            comment(longOne, authorAuth, body).andExpect(status().isCreated());
            comment(shortOne, authorAuth, "  коротко  ").andExpect(status().isCreated());

            assertThat(payloadOf(longOne, TYPE_TASK_COMMENT, "commentExcerpt"))
                    .isEqualTo("я".repeat(140) + "…");
            // Пробелы по краям срезаются и у короткого текста тоже.
            assertThat(payloadOf(shortOne, TYPE_TASK_COMMENT, "commentExcerpt")).isEqualTo("коротко");
        }
    }

    // ----------------------------------------------------------------- чтение и счётчик

    @Nested
    @DisplayName("прочтение")
    class ReadState {

        @Test
        @DisplayName("непрочитанные считаются только свои")
        void unreadCountIsPerRecipient() throws Exception {
            task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);
            scan();

            mockMvc.perform(get("/api/notifications/unread-count").header(AUTHORIZATION, assigneeAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(1));
            mockMvc.perform(get("/api/notifications/unread-count").header(AUTHORIZATION, authorAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(0));
        }

        /**
         * Повторное «прочитано» не переписывает момент прочтения — иначе поллинг
         * колокольчика или второй клик по уведомлению незаметно двигал бы его вперёд, и
         * сортировка «что я видел и когда» перестала бы что-либо значить.
         */
        @Test
        @DisplayName("повторная отметка не сдвигает время прочтения")
        void markingReadTwiceKeepsTheFirstTimestamp() throws Exception {
            task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);
            scan();
            UUID notificationId = singleNotificationId();

            mockMvc.perform(post("/api/notifications/" + notificationId + "/read")
                    .header(AUTHORIZATION, assigneeAuth)).andExpect(status().isNoContent());
            Timestamp firstReadAt = readAtOf(notificationId);

            mockMvc.perform(post("/api/notifications/" + notificationId + "/read")
                    .header(AUTHORIZATION, assigneeAuth)).andExpect(status().isNoContent());

            assertThat(readAtOf(notificationId)).isEqualTo(firstReadAt);
        }

        @Test
        @DisplayName("чужое уведомление отметить нельзя — его для тебя не существует")
        void anotherUsersNotificationIsNotFound() throws Exception {
            task("Overdue", assignee, hoursFromNow(-1), TaskStatus.NEW);
            scan();

            mockMvc.perform(post("/api/notifications/" + singleNotificationId() + "/read")
                            .header(AUTHORIZATION, authorAuth))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NOTIFICATION_NOT_FOUND"));
        }
    }

    // ------------------------------------------------------------------------- хелперы

    /** Прогон сканера дедлайнов — то, что в проде делает @Scheduled раз в 15 минут. */
    private void scan() {
        notificationScheduler.checkDueDates();
    }

    /**
     * Уведомления получателя как «тип:задача». Заголовок задачи берётся из payload —
     * ровно та строка, которую увидит пользователь в колокольчике.
     */
    private List<String> notificationsOf(User recipient) {
        return jdbcTemplate.queryForList("""
                SELECT type || ':' || (payload ->> 'title')
                FROM notifications
                WHERE recipient_id = ?
                """, String.class, recipient.getId());
    }

    private List<String> allNotifications() {
        return jdbcTemplate.queryForList(
                "SELECT type || ':' || (payload ->> 'title') FROM notifications", String.class);
    }

    // CAST(? AS text) — без явного типа Postgres не может выбрать между ->>(jsonb, text)
    // и ->>(jsonb, int) для нетипизированного параметра.
    private String payloadOf(Task task, String type, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT payload ->> CAST(? AS text) FROM notifications WHERE task_id = ? AND type = ?",
                String.class, key, task.getId(), type);
    }

    private UUID singleNotificationId() {
        return jdbcTemplate.queryForObject("SELECT id FROM notifications", UUID.class);
    }

    // Timestamp, а не Instant: java.time.Instant драйвер Postgres не отдаёт и не принимает
    // (getObject/setObject не знают, в какой SQL-тип его класть), а для сравнения «то же
    // самое время или уже другое» этого достаточно.
    private Timestamp readAtOf(UUID notificationId) {
        return jdbcTemplate.queryForObject(
                "SELECT read_at FROM notifications WHERE id = ?", Timestamp.class, notificationId);
    }

    /**
     * Сохранение формы редактирования задачи — тело содержит задачу целиком. Версия (3.4)
     * читается из БД прямо перед запросом: тесты здесь не про блокировки, а задача к этому
     * моменту могла быть уже изменена самим сценарием.
     */
    private ResultActions editTask(Task task, TaskStatus status, User newAssignee, Instant dueDate) throws Exception {
        long version = taskRepository.findById(task.getId()).orElseThrow().getVersion();
        String body = """
                {"title":"%s","status":"%s","urgency":"MEDIUM","assigneeId":%s,"dueDate":%s,"version":%d}"""
                .formatted(task.getTitle(), status,
                        newAssignee != null ? "\"" + newAssignee.getId() + "\"" : "null",
                        dueDate != null ? "\"" + dueDate + "\"" : "null",
                        version);
        return mockMvc.perform(patch("/api/tasks/" + task.getId())
                .header(AUTHORIZATION, authorAuth)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private ResultActions comment(Task task, String auth, String body) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + task.getId() + "/comments")
                .header(AUTHORIZATION, auth)
                .contentType(APPLICATION_JSON)
                .content("{\"body\":\"%s\"}".formatted(body)));
    }

    /**
     * Сдвиг дедлайна мимо API — намеренно: пройти через PATCH значило бы попутно погасить
     * алерты (см. clearDueDateAlerts), а тест про переход due_soon → overdue проверяет
     * именно то, что происходит без чьего-либо участия, когда срок наступает сам.
     */
    private void shiftDueDate(Task task, Instant dueDate) {
        jdbcTemplate.update("UPDATE tasks SET due_date = ? WHERE id = ?",
                Timestamp.from(dueDate), task.getId());
    }

    private static Instant hoursFromNow(long hours) {
        return Instant.now().plus(Duration.ofHours(hours)).truncatedTo(ChronoUnit.SECONDS);
    }

    private static Instant daysFromNow(long days) {
        return Instant.now().plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS);
    }

    private User createUser(String email, String firstName) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        user.setLastName("Тестов");
        user.setFirstName(firstName);
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

    private Task task(String title, User taskAssignee, Instant dueDate, TaskStatus status) {
        Task task = new Task();
        task.setProject(project);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        task.setTitle(title);
        task.setStatus(status);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setPosition(0);
        task.setCreatedBy(author);
        task.setAssignee(taskAssignee);
        task.setDueDate(dueDate);
        return taskRepository.save(task);
    }
}
