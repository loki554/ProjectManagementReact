package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.mail.UnsubscribeTokenService;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pmtracker.project_management_backend.notification.NotificationService.TYPE_TASK_ASSIGNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Email-уведомления (4.3): доставка, настройки, суточная сводка, отписка.
 * <p>
 * Всё, что здесь проверяется, ломается тихо. Забытая проверка настроек — это письма тому,
 * кто от них отписался, то есть жалоба на спам, а не красный тест. Дайджест, не проставивший
 * отметку, шлёт одну и ту же сводку каждое утро. Неработающая ссылка отписки не падает
 * нигде — она просто не работает, и человек вместо неё жмёт «спам», после чего проблемы
 * с доставкой начинаются у всего домена. Ни одно из этих состояний не видно ни в логах, ни
 * в ответах API — только в чужом почтовом ящике.
 * <p>
 * SMTP настоящий (GreenMail, см. {@link IntegrationTest}), и это принципиально: мок
 * {@code MailService} доказал бы, что метод позвали, тогда как проверять хочется само письмо —
 * что оно ушло на нужный адрес, что в нём рабочая ссылка на задачу и что заголовок задачи с
 * переводом строки не превратился в лишний заголовок письма.
 * <p>
 * Дайджест вызывается руками ({@code sendDigests()}), а не ждётся по расписанию: проверять
 * надо не то, что cron умеет срабатывать (это Spring), а что именно собирает один прогон и
 * что делает второй прогон подряд. Чтобы фоновый тик не вмешался посреди теста, в профиле
 * {@code test} расписание сводки отодвинуто на 29 февраля (см. application-test.yml).
 */
class NotificationEmailIntegrationTest extends IntegrationTest {

    /** Ссылка отписки в хвосте каждого письма — из неё же тесты берут токен. */
    private static final Pattern UNSUBSCRIBE_LINK = Pattern.compile("/unsubscribe\\?token=(\\S+)");

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private NotificationSettingsRepository settingsRepository;
    @Autowired private NotificationDigestJob digestJob;
    @Autowired private UnsubscribeTokenService unsubscribeTokenService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User author;
    private User assignee;
    private Project project;
    private String authorAuth;
    private String assigneeAuth;

    @BeforeEach
    void createProject() {
        author = createUser("author@example.com", "Автор");
        assignee = createUser("assignee@example.com", "Исполнитель");

        project = new Project();
        project.setName("Mail project");
        project.setSlug("mail-project");
        project.setCreatedBy(author);
        projectRepository.save(project);

        addMember(author, ProjectRole.OWNER);
        addMember(assignee, ProjectRole.MEMBER);

        authorAuth = "Bearer " + jwtService.generateAccessToken(author);
        assigneeAuth = "Bearer " + jwtService.generateAccessToken(assignee);
    }

    // ------------------------------------------------------------- мгновенная доставка

    @Nested
    @DisplayName("мгновенная доставка")
    class InstantDelivery {

        /**
         * Базовый сценарий целиком: письмо ушло тому, кого назначили, и в нём есть всё, ради
         * чего человек его открывает, — что случилось, где и куда нажать.
         */
        @Test
        @DisplayName("назначение задачи — письмо исполнителю со ссылкой на задачу и отпиской")
        void assignmentSendsAnEmailWithLinks() throws Exception {
            createTask("Починить сборку", assignee);

            MimeMessage message = awaitSingleEmail();

            assertThat(recipientOf(message)).isEqualTo("assignee@example.com");
            assertThat(subjectOf(message)).contains("Починить сборку");
            assertThat(bodyOf(message))
                    .contains("Тестов Автор назначил(а) вам задачу «Починить сборку»")
                    .contains("Проект «Mail project», задача #1")
                    .contains("http://localhost:5173/projects/mail-project/tasks/1")
                    .contains("/unsubscribe?token=");
        }

        @Test
        @DisplayName("комментарий — письмо второй стороне, но не автору комментария")
        void commentSendsAnEmailToTheOtherSide() throws Exception {
            UUID taskId = createTask("Задача с обсуждением", assignee);
            // Письмо о назначении дожидаемся, а не просто чистим ящик: отправка асинхронная,
            // и очистка «сразу после» успевает пройти раньше письма — оно доезжает позже и
            // подменяет собой то, ради которого тест написан.
            awaitSingleEmail();
            clearMailbox();

            // Комментирует исполнитель — письмо должно уйти постановщику, но не самому автору
            // комментария: правило «себе не пишем» одно и то же для колокольчика и для почты.
            comment(taskId, assigneeAuth, "Тут вопрос").andExpect(status().isCreated());

            MimeMessage message = awaitSingleEmail();
            assertThat(recipientOf(message)).isEqualTo("author@example.com");
            assertThat(bodyOf(message)).contains("прокомментировал(а) задачу «Задача с обсуждением»: «Тут вопрос»");
        }

        /**
         * Письмо про @упоминание (4.5) отличается от письма про комментарий и темой, и
         * строчкой события: получателя позвали лично, и по теме в списке писем это должно
         * быть видно, не открывая.
         */
        @Test
        @DisplayName("упоминание — письмо с собственной темой «Вас упомянули»")
        void aMentionSendsItsOwnEmail() throws Exception {
            UUID taskId = createTask("Задача с обсуждением", assignee);
            awaitSingleEmail();
            clearMailbox();

            comment(taskId, authorAuth, "@assignee@example.com глянь, пожалуйста")
                    .andExpect(status().isCreated());

            MimeMessage message = awaitSingleEmail();
            assertThat(recipientOf(message)).isEqualTo("assignee@example.com");
            assertThat(subjectOf(message)).contains("Вас упомянули в задаче «Задача с обсуждением»");
            assertThat(bodyOf(message))
                    .contains("Тестов Автор упомянул(а) вас в задаче «Задача с обсуждением»")
                    .contains("http://localhost:5173/projects/mail-project/tasks/1");
        }

        /**
         * Отдельный флаг настроек у упоминаний нужен ровно ради этого: «в треде моей задачи
         * опять пишут» человек выключает часто, а «меня позвали по имени» — почти никогда,
         * и одним переключателем эти два случая делить нельзя.
         */
        @Test
        @DisplayName("выключенные письма о комментариях не выключают письма об упоминаниях")
        void turningOffCommentEmailsKeepsMentionEmails() throws Exception {
            settings(assignee, s -> {
                s.setTaskAssigned(false);
                s.setTaskComment(false);
            });
            UUID taskId = createTask("Задача с обсуждением", assignee);
            assertNoEmailSent();

            comment(taskId, authorAuth, "просто комментарий").andExpect(status().isCreated());
            assertNoEmailSent();

            comment(taskId, authorAuth, "@assignee@example.com а вот это важно")
                    .andExpect(status().isCreated());

            assertThat(subjectOf(awaitSingleEmail())).contains("Вас упомянули");
        }

        @Test
        @DisplayName("назначение на себя писем не порождает — уведомления тоже нет")
        void selfAssignmentSendsNothing() throws Exception {
            createTask("Сам себе", author);

            assertNoEmailSent();
            assertThat(notificationCount()).isZero();
        }

        /**
         * Выключенный тип — не «отправим позже», а «не отправим». Отметка {@code email_sent_at}
         * не ставится: письма не было, и колонка с таким названием обязана это отражать —
         * иначе по базе не отличить «написали» от «решили не писать».
         */
        @Test
        @DisplayName("выключенный тип: уведомление в колокольчике есть, письма нет")
        void disabledTypeStillCreatesTheBellNotification() throws Exception {
            settings(assignee, s -> s.setTaskAssigned(false));

            createTask("Тихая задача", assignee);

            assertNoEmailSent();
            assertThat(notificationCount()).isEqualTo(1);
            assertThat(pendingEmailCount(assignee)).isEqualTo(1);
        }

        @Test
        @DisplayName("общий выключатель гасит письма всех типов")
        void disabledEmailStopsEverything() throws Exception {
            settings(assignee, s -> s.setEmailEnabled(false));

            createTask("Тихая задача", assignee);

            assertNoEmailSent();
            assertThat(notificationCount()).isEqualTo(1);
        }

        /**
         * Единственное место приложения, где пользовательский текст попадает в ЗАГОЛОВОК
         * письма. Заголовок задачи ничем не ограничен и вполне может содержать перевод
         * строки — подставленный в тему как есть, он позволил бы дописать письму собственные
         * заголовки, например Bcc. Признак провала здесь не «в теме появился мусор», а
         * второе письмо на чужой адрес, поэтому проверяем и то, и другое.
         */
        @Test
        @DisplayName("перевод строки в заголовке задачи не дописывает письму заголовков")
        void newlinesInTheTaskTitleCannotInjectHeaders() throws Exception {
            createTaskWithRawJsonTitle("\"Срыв\\nBcc: attacker@example.com\"", assignee);

            MimeMessage message = awaitSingleEmail();

            assertThat(message.getAllRecipients()).hasSize(1);
            assertThat(recipientOf(message)).isEqualTo("assignee@example.com");
            assertThat(subjectOf(message)).doesNotContain("\n").doesNotContain("\r");
        }
    }

    // ------------------------------------------------------------------ суточная сводка

    @Nested
    @DisplayName("суточная сводка")
    class DailyDigest {

        @Test
        @DisplayName("в режиме дайджеста мгновенных писем нет, а прогон присылает одно на всё")
        void digestCollectsEverythingIntoOneEmail() throws Exception {
            settings(assignee, s -> s.setMode(NotificationDeliveryMode.DAILY_DIGEST));

            UUID first = createTask("Первая", assignee);
            createTask("Вторая", assignee);
            comment(first, authorAuth, "И ещё вот это").andExpect(status().isCreated());
            assertNoEmailSent();

            digestJob.sendDigests();

            MimeMessage message = awaitSingleEmail();
            assertThat(recipientOf(message)).isEqualTo("assignee@example.com");
            assertThat(subjectOf(message)).contains("(3)");
            assertThat(bodyOf(message))
                    .contains("назначил(а) вам задачу «Первая»")
                    .contains("назначил(а) вам задачу «Вторая»")
                    .contains("прокомментировал(а) задачу «Первая»: «И ещё вот это»");
        }

        /**
         * Ради чего вообще заведена колонка email_sent_at: без отметки один и тот же список
         * уходил бы каждое утро, пока уведомления не выпадут из окна.
         */
        @Test
        @DisplayName("второй прогон подряд не повторяет вчерашнюю сводку")
        void aSecondRunSendsNothing() throws Exception {
            settings(assignee, s -> s.setMode(NotificationDeliveryMode.DAILY_DIGEST));
            createTask("Первая", assignee);

            digestJob.sendDigests();
            awaitSingleEmail();
            clearMailbox();

            digestJob.sendDigests();

            assertNoEmailSent();
        }

        /**
         * Обратная сторона: переключение режима не должно превращаться в повтор того, о чём
         * уже написали мгновенно. Отметка ставится в момент отправки, поэтому вечерняя сводка
         * такие уведомления не видит.
         */
        @Test
        @DisplayName("то, что уже ушло мгновенным письмом, в сводку не попадает")
        void instantlySentNotificationsAreNotRepeated() throws Exception {
            createTask("Уже отправлено", assignee);
            awaitSingleEmail();
            clearMailbox();

            settings(assignee, s -> s.setMode(NotificationDeliveryMode.DAILY_DIGEST));
            digestJob.sendDigests();

            assertNoEmailSent();
        }

        /**
         * Верхняя граница окна нужна не меньше нижней: без неё первая же сводка человека,
         * только что выбравшего этот режим, стала бы письмом на всю его историю уведомлений —
         * там ведь тоже email_sent_at пуст.
         */
        @Test
        @DisplayName("старее окна в сводку не берётся")
        void notificationsOlderThanTheWindowAreSkipped() throws Exception {
            settings(assignee, s -> s.setMode(NotificationDeliveryMode.DAILY_DIGEST));
            createTask("Позавчерашняя", assignee);
            ageAllNotifications(NotificationDigestJob.WINDOW.toHours() + 1);

            digestJob.sendDigests();

            assertNoEmailSent();
        }

        @Test
        @DisplayName("выключенный тип в сводку не попадает и отправленным не помечается")
        void disabledTypesStayOutOfTheDigest() throws Exception {
            settings(assignee, s -> {
                s.setMode(NotificationDeliveryMode.DAILY_DIGEST);
                s.setTaskAssigned(false);
            });
            createTask("Не про меня", assignee);

            digestJob.sendDigests();

            assertNoEmailSent();
            assertThat(pendingEmailCount(assignee)).isEqualTo(1);
        }

        @Test
        @DisplayName("режим соседа на сводку не влияет — письмо уходит только выбравшему её")
        void onlyDigestUsersGetDigests() throws Exception {
            settings(assignee, s -> s.setMode(NotificationDeliveryMode.DAILY_DIGEST));
            createTask("Для исполнителя", assignee);

            UUID taskId = createTask("Для автора", author);
            comment(taskId, assigneeAuth, "Вопрос автору").andExpect(status().isCreated());
            // Автор в режиме по умолчанию — его письмо ушло сразу, до всякой сводки.
            assertThat(recipientOf(awaitSingleEmail())).isEqualTo("author@example.com");
            clearMailbox();

            digestJob.sendDigests();

            assertThat(recipientOf(awaitSingleEmail())).isEqualTo("assignee@example.com");
        }

        /**
         * Длинная сводка режется по числу строк, но отметку получают ВСЕ вошедшие в неё
         * уведомления — иначе завтрашнее письмо начиналось бы с сегодняшнего хвоста, и так
         * по кругу. Поэтому про отрезанное надо честно написать в самом письме.
         */
        @Test
        @DisplayName("длинная сводка обрезается, но помечается отправленной целиком")
        void anOversizedDigestIsTruncatedButFullyMarked() throws Exception {
            settings(assignee, s -> s.setMode(NotificationDeliveryMode.DAILY_DIGEST));
            int total = NotificationDigestJob.MAX_ITEMS_PER_EMAIL + 2;
            insertNotifications(assignee, total);

            digestJob.sendDigests();

            MimeMessage message = awaitSingleEmail();
            assertThat(subjectOf(message)).contains("(" + total + ")");
            assertThat(bodyOf(message)).contains("и ещё 2");
            assertThat(pendingEmailCount(assignee)).isZero();

            clearMailbox();
            digestJob.sendDigests();
            assertNoEmailSent();
        }
    }

    // ----------------------------------------------------------------------- настройки

    @Nested
    @DisplayName("настройки")
    class Settings {

        /**
         * Строка настроек не заводится при регистрации, поэтому «настроек нет» — это рабочее
         * состояние, а не пробел в данных: отдаём значения по умолчанию.
         */
        @Test
        @DisplayName("человеку, ничего не менявшему, отдаются значения по умолчанию")
        void defaultsAreReturnedWithoutARow() throws Exception {
            mockMvc.perform(get("/api/users/me/notification-settings").header(AUTHORIZATION, assigneeAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.emailEnabled").value(true))
                    .andExpect(jsonPath("$.mode").value("INSTANT"))
                    .andExpect(jsonPath("$.taskAssigned").value(true))
                    .andExpect(jsonPath("$.taskComment").value(true))
                    .andExpect(jsonPath("$.taskMention").value(true))
                    .andExpect(jsonPath("$.taskDueSoon").value(true))
                    .andExpect(jsonPath("$.taskOverdue").value(true));

            assertThat(settingsRepository.findById(assignee.getId())).isEmpty();
        }

        @Test
        @DisplayName("сохранённые настройки читаются обратно и действуют на рассылку")
        void savedSettingsAreAppliedToDelivery() throws Exception {
            updateSettings(assigneeAuth, """
                    {"emailEnabled":true,"mode":"DAILY_DIGEST","taskAssigned":false,
                     "taskComment":true,"taskMention":true,"taskDueSoon":true,"taskOverdue":true}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("DAILY_DIGEST"))
                    .andExpect(jsonPath("$.taskAssigned").value(false));

            mockMvc.perform(get("/api/users/me/notification-settings").header(AUTHORIZATION, assigneeAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("DAILY_DIGEST"))
                    .andExpect(jsonPath("$.taskAssigned").value(false));

            createTask("Не про меня", assignee);
            digestJob.sendDigests();
            assertNoEmailSent();
        }

        /**
         * Все поля обязательны намеренно: с примитивным boolean отсутствующее в JSON поле
         * молча стало бы false, то есть опечатка в имени поля выключала бы человеку часть
         * уведомлений и никак себя не проявляла.
         */
        @Test
        @DisplayName("неполное тело — 400, а не молча выключенные уведомления")
        void aPartialBodyIsRejected() throws Exception {
            updateSettings(assigneeAuth, """
                    {"emailEnabled":true,"mode":"INSTANT"}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

            assertThat(settingsRepository.findById(assignee.getId())).isEmpty();
        }

        @Test
        @DisplayName("настройки без входа не читаются")
        void settingsRequireAuthentication() throws Exception {
            mockMvc.perform(get("/api/users/me/notification-settings"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------------------- отписка

    @Nested
    @DisplayName("отписка по ссылке из письма")
    class Unsubscribe {

        /**
         * Сквозной сценарий отписки — ровно то, что делает человек: берёт ссылку из письма,
         * жмёт кнопку, не входя в систему, и писем больше не получает.
         */
        @Test
        @DisplayName("токен из письма выключает письма и не требует входа")
        void theTokenFromTheEmailStopsFurtherEmails() throws Exception {
            createTask("Первая", assignee);
            String token = unsubscribeTokenFrom(awaitSingleEmail());
            clearMailbox();

            unsubscribe(token).andExpect(status().isNoContent());

            createTask("Вторая", assignee);
            assertNoEmailSent();
            assertThat(notificationCount()).isEqualTo(2);
        }

        /**
         * Отписка гасит только общий выключатель. Стереть заодно режим и набор типов значило
         * бы, что «я передумал» в профиле нечем отменить — настройки пришлось бы собирать
         * заново, а человек их уже один раз собрал.
         */
        @Test
        @DisplayName("отписка не стирает режим и набор типов")
        void unsubscribingKeepsTheRestOfThePreferences() throws Exception {
            settings(assignee, s -> {
                s.setMode(NotificationDeliveryMode.DAILY_DIGEST);
                s.setTaskComment(false);
            });

            unsubscribe(unsubscribeTokenService.tokenFor(assignee.getId())).andExpect(status().isNoContent());

            mockMvc.perform(get("/api/users/me/notification-settings").header(AUTHORIZATION, assigneeAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.emailEnabled").value(false))
                    .andExpect(jsonPath("$.mode").value("DAILY_DIGEST"))
                    .andExpect(jsonPath("$.taskComment").value(false));
        }

        @Test
        @DisplayName("мусор вместо токена — 400")
        void garbageIsRejected() throws Exception {
            unsubscribe("не-токен-вовсе")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
        }

        /**
         * Токен — это id плюс подпись, и без подписи он ничего не стоит: иначе отписать
         * любого можно было бы, зная только его id, а id пользователя видит всякий, кто
         * открыл список участников проекта.
         */
        @Test
        @DisplayName("подменённая подпись не проходит")
        void aTamperedSignatureIsRejected() throws Exception {
            String valid = unsubscribeTokenService.tokenFor(assignee.getId());
            String tampered = valid.substring(0, valid.indexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAA";

            unsubscribe(tampered)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));

            assertThat(settingsRepository.findById(assignee.getId())).isEmpty();
        }

        @Test
        @DisplayName("чужой id со своей подписью отписывает только своего владельца")
        void aTokenOnlyUnsubscribesItsOwner() throws Exception {
            unsubscribe(unsubscribeTokenService.tokenFor(assignee.getId())).andExpect(status().isNoContent());

            assertThat(settingsRepository.findById(assignee.getId()).orElseThrow().isEmailEnabled()).isFalse();
            assertThat(settingsRepository.findById(author.getId())).isEmpty();
        }

        /**
         * Токен валиден, отписывать некого. Отвечаем как при успехе: разница в ответе дала бы
         * способ проверять ссылкой из старого письма, жив ли ещё аккаунт.
         */
        @Test
        @DisplayName("токен удалённого пользователя принимается молча")
        void aTokenOfADeletedUserIsAcceptedSilently() throws Exception {
            String token = unsubscribeTokenService.tokenFor(UUID.randomUUID());

            unsubscribe(token).andExpect(status().isNoContent());
        }
    }

    // ------------------------------------------------------------------------- хелперы

    /** @return id созданной задачи. Создание через API — вместе со всеми уведомлениями. */
    private UUID createTask(String title, User taskAssignee) throws Exception {
        return createTaskWithRawJsonTitle("\"" + title + "\"", taskAssignee);
    }

    /**
     * Заголовок подставляется куском готового JSON, а не строкой: тест про header injection
     * должен прислать заголовок с настоящим переводом строки внутри, то есть с экранированием,
     * которое собирается вручную.
     */
    private UUID createTaskWithRawJsonTitle(String jsonTitle, User taskAssignee) throws Exception {
        String body = """
                {"title":%s,"urgency":"MEDIUM","assigneeId":"%s"}"""
                .formatted(jsonTitle, taskAssignee.getId());
        String response = mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                        .header(AUTHORIZATION, authorAuth)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(response);
        if (!matcher.find()) {
            throw new AssertionError("No task id in the response: " + response);
        }
        return UUID.fromString(matcher.group(1));
    }

    private ResultActions comment(UUID taskId, String auth, String body) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/comments")
                .header(AUTHORIZATION, auth)
                .contentType(APPLICATION_JSON)
                .content("{\"body\":\"%s\"}".formatted(body)));
    }

    private ResultActions updateSettings(String auth, String body) throws Exception {
        return mockMvc.perform(patch("/api/users/me/notification-settings")
                .header(AUTHORIZATION, auth)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    /** Отписка идёт без Authorization — эндпоинт публичный, в этом весь смысл. */
    private ResultActions unsubscribe(String token) throws Exception {
        return mockMvc.perform(post("/api/notifications/unsubscribe")
                .contentType(APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(token)));
    }

    private void settings(User user, java.util.function.Consumer<NotificationSettings> customizer) {
        NotificationSettings settings = new NotificationSettings();
        settings.setUserId(user.getId());
        customizer.accept(settings);
        settingsRepository.save(settings);
    }

    /** Сколько уведомлений получателя ещё не уехало письмом (email_sent_at IS NULL). */
    private int pendingEmailCount(User recipient) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND email_sent_at IS NULL",
                Integer.class, recipient.getId());
    }

    private int notificationCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Integer.class);
    }

    /** Состаривает уже созданные уведомления — способ заглянуть за окно сводки, не выжидая его. */
    private void ageAllNotifications(long hours) {
        jdbcTemplate.update("UPDATE notifications SET created_at = now() - (? || ' hours')::interval",
                String.valueOf(hours));
    }

    /**
     * Уведомления вставляются напрямую: тесту нужен объём (больше, чем помещается в письмо),
     * а не путь, которым он получен, — три десятка задач через API добавили бы к прогону
     * секунды и ничего к проверке.
     */
    private void insertNotifications(User recipient, int count) {
        for (int i = 1; i <= count; i++) {
            jdbcTemplate.update("""
                            INSERT INTO notifications (recipient_id, type, payload)
                            VALUES (?, ?, CAST(? AS jsonb))
                            """,
                    recipient.getId(), TYPE_TASK_ASSIGNED,
                    "{\"title\":\"Задача %d\",\"taskNumber\":%d,\"projectSlug\":\"mail-project\",\"projectName\":\"Mail project\"}"
                            .formatted(i, i));
        }
    }

    private static String unsubscribeTokenFrom(MimeMessage message) {
        Matcher matcher = UNSUBSCRIBE_LINK.matcher(bodyOf(message));
        if (!matcher.find()) {
            throw new AssertionError("The email has no unsubscribe link");
        }
        return matcher.group(1);
    }

    private static String subjectOf(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read the email subject", e);
        }
    }

    /** getContent(), а не сырые байты: JavaMail сам снимает transfer encoding и кодировку. */
    private static String bodyOf(MimeMessage message) {
        try {
            return message.getContent().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read the email body", e);
        }
    }

    private static String recipientOf(MimeMessage message) {
        try {
            return message.getAllRecipients()[0].toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read the email recipient", e);
        }
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
}
