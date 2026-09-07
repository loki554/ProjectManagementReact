package com.pmtracker.project_management_backend.comment;

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
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.pmtracker.project_management_backend.notification.NotificationService.TYPE_TASK_COMMENT;
import static com.pmtracker.project_management_backend.notification.NotificationService.TYPE_TASK_MENTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Правка комментариев (4.4) и @упоминания в них (4.5).
 * <p>
 * Оба пункта трогают одно и то же тело комментария, и проверять их порознь было бы
 * неправильно: самое интересное здесь как раз на их стыке — что происходит с упоминаниями,
 * когда текст переписали. Наивная реализация («разобрать новый текст и уведомить всех, кого
 * нашли») выглядит рабочей и рассылает повторное «вас упомянули» каждому, кого в комментарии
 * звали, при каждой исправленной запятой. Ошибка не видна ни в одном ответе API — только у
 * получателя в почте.
 * <p>
 * Вторая вещь, которую здесь ловят: упоминание — это <b>права</b>. Список получателей
 * уведомления собирается из текста на сервере (см. {@code MentionParser}) и сводится к
 * участникам этого проекта; сломанный фильтр по проекту означал бы способ прислать
 * уведомление любому, чей адрес угадали, из проекта, к которому он отношения не имеет.
 */
class CommentEditAndMentionIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User author;
    private User assignee;
    private User bystander;
    private User stranger;
    private Project project;
    private Task task;
    private String authorAuth;
    private String assigneeAuth;
    private String ownerAuth;

    @BeforeEach
    void createFixture() {
        User owner = createUser("owner@example.com", "Владелец");
        author = createUser("author@example.com", "Автор");
        assignee = createUser("assignee@example.com", "Исполнитель");
        bystander = createUser("bystander@example.com", "Посторонний");
        stranger = createUser("stranger@example.com", "Чужак");

        project = new Project();
        project.setName("Comment project");
        project.setSlug("comment-project");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        addMember(owner, ProjectRole.OWNER);
        addMember(author, ProjectRole.MEMBER);
        addMember(assignee, ProjectRole.MEMBER);
        addMember(bystander, ProjectRole.MEMBER);
        // stranger в проекте не состоит — на нём проверяется фильтр упоминаний.

        task = task("Задача", author, assignee);

        ownerAuth = bearer(owner);
        authorAuth = bearer(author);
        assigneeAuth = bearer(assignee);
    }

    // ------------------------------------------------------------------ 4.4: правка

    @Nested
    @DisplayName("правка комментария")
    class Editing {

        @Test
        @DisplayName("автор правит свой комментарий: новый текст и отметка «изменено»")
        void theAuthorCanEditTheirOwnComment() throws Exception {
            UUID commentId = createComment(authorAuth, "Первоначальный текст с апечаткой");

            editComment(authorAuth, commentId, "Первоначальный текст с опечаткой")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.body").value("Первоначальный текст с опечаткой"))
                    .andExpect(jsonPath("$.editedAt").isNotEmpty());

            assertThat(bodyOf(commentId)).isEqualTo("Первоначальный текст с опечаткой");
        }

        /**
         * null, а не «время создания»: пометка «изменено» должна появляться ровно у тех
         * комментариев, которые правили. Отдай сервер здесь createdAt — интерфейсу
         * пришлось бы сравнивать два timestamptz, и пометка стояла бы на всех.
         */
        @Test
        @DisplayName("нетронутый комментарий приходит с editedAt = null")
        void anUntouchedCommentHasNoEditMark() throws Exception {
            createComment(authorAuth, "Как есть");

            mockMvc.perform(get("/api/tasks/" + task.getId() + "/comments").header(AUTHORIZATION, authorAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].editedAt").doesNotExist());
        }

        /**
         * Главная строчка пункта. Удалить чужой комментарий OWNER может — это модерация, и
         * она видна: сообщение исчезает целиком. Переписать чужой текст нельзя никому:
         * подпись осталась бы прежней, а слова стали бы другими.
         */
        @Test
        @DisplayName("владелец проекта не может переписать чужой комментарий")
        void moderatorsCannotRewriteSomeoneElsesWords() throws Exception {
            UUID commentId = createComment(authorAuth, "Я против этого решения");

            editComment(ownerAuth, commentId, "Я за это решение")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("NOT_COMMENT_AUTHOR"));

            assertThat(bodyOf(commentId)).isEqualTo("Я против этого решения");
        }

        /** А удалить — по-прежнему может: модерация никуда не делась. */
        @Test
        @DisplayName("удалить чужой комментарий владелец по-прежнему может")
        void moderationByDeletionStillWorks() throws Exception {
            UUID commentId = createComment(authorAuth, "Что-то лишнее");

            mockMvc.perform(delete("/api/comments/" + commentId).header(AUTHORIZATION, ownerAuth))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("сохранение того же текста не вешает пометку «изменено»")
        void savingTheSameTextIsNotAnEdit() throws Exception {
            UUID commentId = createComment(authorAuth, "Ничего не менял");

            editComment(authorAuth, commentId, "Ничего не менял")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.editedAt").doesNotExist());
        }

        @Test
        @DisplayName("пустое тело — 400, комментарий остаётся прежним")
        void anEmptyBodyIsRejected() throws Exception {
            UUID commentId = createComment(authorAuth, "Осмысленный текст");

            editComment(authorAuth, commentId, "   ")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

            assertThat(bodyOf(commentId)).isEqualTo("Осмысленный текст");
        }

        @Test
        @DisplayName("текст длиннее 2000 символов не проходит и через правку")
        void theLengthLimitAppliesToEditsToo() throws Exception {
            UUID commentId = createComment(authorAuth, "Коротко");

            editComment(authorAuth, commentId, "я".repeat(2001))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("несуществующий комментарий — 404")
        void editingAMissingCommentIs404() throws Exception {
            editComment(authorAuth, UUID.randomUUID(), "текст")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("COMMENT_NOT_FOUND"));
        }

        /**
         * Правка — не событие проекта. В ленте уже стоит «добавил комментарий», и вторая
         * запись «отредактировал комментарий» рядом означала бы, что лента заполняется
         * исправленными опечатками. Заодно это проверка того, что правка вообще ничего не
         * дописывает в project_activity.
         */
        @Test
        @DisplayName("правка не пишется в ленту активности")
        void editingDoesNotShowUpInTheActivityFeed() throws Exception {
            UUID commentId = createComment(authorAuth, "Первая версия");
            editComment(authorAuth, commentId, "Вторая версия").andExpect(status().isOk());

            assertThat(activityTypes()).containsExactly("comment_added");
        }

        /**
         * search_vector у task_comments — генерируемая колонка (V23), то есть за
         * переиндексацию отвечает БД. Тест не про Postgres, а про то, что колонку не
         * обошли: если правка когда-нибудь поедет через нативный UPDATE с явным списком
         * колонок, поиск начнёт находить текст, которого в комментарии больше нет, и
         * заметить это по ответам API будет нечем.
         */
        @Test
        @DisplayName("поиск после правки находит новый текст и не находит старый")
        void editingReindexesTheComment() throws Exception {
            UUID commentId = createComment(authorAuth, "Проблема в биллинге");

            editComment(authorAuth, commentId, "Проблема в авторизации").andExpect(status().isOk());

            mockMvc.perform(get("/api/search").param("q", "авторизация").header(AUTHORIZATION, authorAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1));
            mockMvc.perform(get("/api/search").param("q", "биллинг").header(AUTHORIZATION, authorAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(0));
        }
    }

    // -------------------------------------------------------------- 4.5: упоминания

    @Nested
    @DisplayName("@упоминания")
    class Mentions {

        @Test
        @DisplayName("упомянутый участник получает task_mention")
        void mentioningAMemberNotifiesThem() throws Exception {
            createComment(authorAuth, "Посмотри, пожалуйста, @bystander");

            assertThat(notificationsOf(bystander)).containsExactly(TYPE_TASK_MENTION);
        }

        /**
         * Упоминание сильнее «прокомментировали вашу задачу»: человека позвали по имени, и
         * прийти к нему должно именно это. Двух уведомлений об одном комментарии не бывает.
         */
        @Test
        @DisplayName("упоминание исполнителя приходит как task_mention, а не как task_comment")
        void aMentionOutranksTheCommentNotification() throws Exception {
            createComment(authorAuth, "@assignee глянь");

            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_MENTION);
        }

        @Test
        @DisplayName("остальные участники задачи получают обычный task_comment")
        void everyoneElseStillGetsTheCommentNotification() throws Exception {
            // Комментирует исполнитель и зовёт постороннего; постановщик (author) при этом
            // должен получить обычное «прокомментировали вашу задачу».
            createComment(assigneeAuth, "@bystander посмотри");

            assertThat(notificationsOf(bystander)).containsExactly(TYPE_TASK_MENTION);
            assertThat(notificationsOf(author)).containsExactly(TYPE_TASK_COMMENT);
        }

        @Test
        @DisplayName("упоминание себя ничего не присылает")
        void mentioningYourselfIsSilent() throws Exception {
            createComment(assigneeAuth, "напоминание себе: @assignee");

            assertThat(notificationsOf(assignee)).isEmpty();
        }

        /**
         * Фильтр по участникам проекта — это и есть проверка прав упоминания. Посторонний
         * не должен получать уведомления из проекта, к которому не имеет отношения, даже
         * если его адрес кто-то знает.
         */
        @Test
        @DisplayName("упоминание человека не из проекта никого не уведомляет")
        void mentioningAnOutsiderNotifiesNobody() throws Exception {
            comment(authorAuth, "@stranger, а ты что думаешь?")
                    .andExpect(status().isCreated());

            assertThat(notificationsOf(stranger)).isEmpty();
        }

        @Test
        @DisplayName("несуществующий никнейм не мешает отправить комментарий")
        void aTypoInAMentionDoesNotBlockTheComment() throws Exception {
            comment(authorAuth, "@no-such-person привет").andExpect(status().isCreated());
        }

        /**
         * «Пишите на bystander@example.com» — это почтовый адрес в тексте, а не обращение.
         * Отличает их ровно одно: символ перед «@». Ошибка здесь означала бы уведомления
         * всякий раз, когда кто-то скопировал в комментарий строчку из письма — а локальная
         * часть адреса, выведенная из того же имени, совпадает с никнеймом чаще всего.
         */
        @Test
        @DisplayName("почтовый адрес в тексте упоминанием не считается")
        void aBareEmailInTheTextIsNotAMention() throws Exception {
            createComment(authorAuth, "Отправь отчёт на bystander@example.com, он ждёт");

            assertThat(notificationsOf(bystander)).isEmpty();
        }

        /**
         * Точка не входит в набор символов никнейма (V28) именно ради этого: конец
         * предложения не должен ни съедаться упоминанием, ни мешать его разобрать.
         */
        @Test
        @DisplayName("точка сразу после никнейма упоминанию не мешает")
        void aTrailingPeriodDoesNotBreakTheMention() throws Exception {
            createComment(authorAuth, "Это к @bystander.");

            assertThat(notificationsOf(bystander)).containsExactly(TYPE_TASK_MENTION);
        }

        /**
         * Без верхней границы длины «@» и тридцать пять символов подряд дали бы упоминание
         * из первых тридцати — то есть чужой никнейм, собранный из куска чужого слова.
         */
        @Test
        @DisplayName("слишком длинная строка после @ упоминанием не считается")
        void anOverlongTokenIsNotAMention() throws Exception {
            comment(authorAuth, "@" + "a".repeat(35)).andExpect(status().isCreated());

            // Обычные уведомления о комментарии при этом никуда не деваются — проверяем
            // именно отсутствие упоминаний, а не тишину вообще.
            assertThat(allNotifications()).doesNotContain(TYPE_TASK_MENTION);
        }

        @Test
        @DisplayName("регистр в адресе не мешает упоминанию сработать")
        void mentionsAreCaseInsensitive() throws Exception {
            createComment(authorAuth, "@Bystander глянь");

            assertThat(notificationsOf(bystander)).containsExactly(TYPE_TASK_MENTION);
        }

        @Test
        @DisplayName("один человек, упомянутый дважды, получает одно уведомление")
        void repeatingAMentionDoesNotDoubleTheNotification() throws Exception {
            createComment(authorAuth, "@bystander и ещё раз @bystander");

            assertThat(notificationsOf(bystander)).containsExactly(TYPE_TASK_MENTION);
        }

        /**
         * Потолок в 20 адресов. В 2000 символов их помещается около сотни, а каждый — это
         * уведомление и, по умолчанию, письмо; без ограничения один комментарий рассылал бы
         * почту всему большому проекту.
         */
        @Test
        @DisplayName("упоминаний больше потолка — уведомляются первые двадцать")
        void theNumberOfMentionsIsCapped() throws Exception {
            List<User> crowd = IntStream.rangeClosed(1, 25)
                    .mapToObj(i -> {
                        User member = createUser("crowd" + i + "@example.com", "Участник" + i);
                        addMember(member, ProjectRole.MEMBER);
                        return member;
                    })
                    .toList();
            String body = crowd.stream()
                    .map(user -> "@" + user.getUsername())
                    .collect(Collectors.joining(" "));

            comment(authorAuth, body).andExpect(status().isCreated());

            long notified = crowd.stream().filter(user -> !notificationsOf(user).isEmpty()).count();
            assertThat(notified).isEqualTo(20);
            // Отрезается именно хвост: работает начало списка, которое человек и писал.
            assertThat(notificationsOf(crowd.get(0))).containsExactly(TYPE_TASK_MENTION);
            assertThat(notificationsOf(crowd.get(24))).isEmpty();
        }
    }

    // -------------------------------------------------- стык 4.4 и 4.5: правка и зов

    @Nested
    @DisplayName("упоминания при правке")
    class MentionsOnEdit {

        /**
         * Ради чего сравниваются старая и новая редакции: поправленная опечатка не должна
         * звать в тред заново всех, кто в нём уже упомянут.
         */
        @Test
        @DisplayName("уже упомянутого правка не уведомляет повторно")
        void anAlreadyMentionedPersonIsNotCalledTwice() throws Exception {
            UUID commentId = createComment(authorAuth, "@bystander глянь пожалуста");
            assertThat(notificationsOf(bystander)).containsExactly(TYPE_TASK_MENTION);

            editComment(authorAuth, commentId, "@bystander глянь пожалуйста")
                    .andExpect(status().isOk());

            assertThat(notificationsOf(bystander)).containsExactly(TYPE_TASK_MENTION);
        }

        @Test
        @DisplayName("добавленное правкой упоминание уведомляет")
        void aMentionAddedByAnEditNotifies() throws Exception {
            UUID commentId = createComment(authorAuth, "Надо посмотреть");
            assertThat(notificationsOf(bystander)).isEmpty();

            editComment(authorAuth, commentId, "Надо посмотреть, @bystander")
                    .andExpect(status().isOk());

            assertThat(notificationsOf(bystander)).containsExactly(TYPE_TASK_MENTION);
        }

        /**
         * Комментарий тот же самый — постановщик и исполнитель о нём уже знают. Повторное
         * «прокомментировал вашу задачу» на каждую правку было бы худшим видом шума:
         * событие, которого не было.
         */
        @Test
        @DisplayName("правка не присылает task_comment заново")
        void editingDoesNotRepeatTheCommentNotification() throws Exception {
            UUID commentId = createComment(authorAuth, "Первая версия");
            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_COMMENT);

            editComment(authorAuth, commentId, "Вторая версия").andExpect(status().isOk());

            assertThat(notificationsOf(assignee)).containsExactly(TYPE_TASK_COMMENT);
        }

        @Test
        @DisplayName("убранное из текста упоминание не отзывает уже отправленное уведомление")
        void removingAMentionKeepsTheNotificationThatWasAlreadySent() throws Exception {
            UUID commentId = createComment(authorAuth, "@bystander глянь");

            editComment(authorAuth, commentId, "уже не актуально").andExpect(status().isOk());

            // Уведомление о том, что человека звали, остаётся: его уже прочитали (и, скорее
            // всего, уже прислали почтой), а задним числом «этого не было» не бывает.
            assertThat(notificationsOf(bystander)).containsExactly(TYPE_TASK_MENTION);
        }

        @Test
        @DisplayName("возвращённое обратно упоминание зовёт человека заново")
        void reAddingAMentionCallsThePersonAgain() throws Exception {
            UUID commentId = createComment(authorAuth, "@bystander глянь");
            editComment(authorAuth, commentId, "уже не актуально").andExpect(status().isOk());

            editComment(authorAuth, commentId, "всё-таки глянь, @bystander")
                    .andExpect(status().isOk());

            // Два уведомления — так и задумано: между ними человека из треда отпустили, и
            // второй зов это отдельное обращение, а не дубль первого.
            assertThat(notificationsOf(bystander))
                    .containsExactly(TYPE_TASK_MENTION, TYPE_TASK_MENTION);
        }
    }

    // ------------------------------------------------------------------- инструменты

    private UUID createComment(String auth, String body) throws Exception {
        String response = comment(auth, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private ResultActions comment(String auth, String body) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + task.getId() + "/comments")
                .header(AUTHORIZATION, auth)
                .contentType(APPLICATION_JSON)
                .content(json(body)));
    }

    private ResultActions editComment(String auth, UUID commentId, String body) throws Exception {
        return mockMvc.perform(patch("/api/comments/" + commentId)
                .header(AUTHORIZATION, auth)
                .contentType(APPLICATION_JSON)
                .content(json(body)));
    }

    /** Тела комментариев в тестах содержат кавычки и @ — экранируем по-настоящему. */
    private static String json(String body) {
        return "{\"body\":\"" + body.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private String bodyOf(UUID commentId) {
        return jdbcTemplate.queryForObject("SELECT body FROM task_comments WHERE id = ?", String.class, commentId);
    }

    private List<String> notificationsOf(User recipient) {
        return jdbcTemplate.queryForList(
                "SELECT type FROM notifications WHERE recipient_id = ? ORDER BY created_at, type",
                String.class, recipient.getId());
    }

    private List<String> allNotifications() {
        return jdbcTemplate.queryForList("SELECT type FROM notifications", String.class);
    }

    private List<String> activityTypes() {
        return jdbcTemplate.queryForList(
                "SELECT type FROM project_activity WHERE project_id = ? ORDER BY created_at",
                String.class, project.getId());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
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
        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }

    private Task task(String title, User createdBy, User taskAssignee) {
        Task created = new Task();
        created.setProject(project);
        created.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        created.setTitle(title);
        created.setStatus(TaskStatus.NEW);
        created.setUrgency(TaskUrgency.MEDIUM);
        created.setPosition(0);
        created.setCreatedBy(createdBy);
        created.setAssignee(taskAssignee);
        return taskRepository.save(created);
    }
}
