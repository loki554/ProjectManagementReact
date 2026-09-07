package com.pmtracker.project_management_backend.task;

import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.category.Category;
import com.pmtracker.project_management_backend.category.CategoryRepository;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.tag.TagRepository;
import com.pmtracker.project_management_backend.timelog.TimeLog;
import com.pmtracker.project_management_backend.timelog.TimeLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pmtracker.project_management_backend.task.TaskStatus.DONE;
import static com.pmtracker.project_management_backend.task.TaskStatus.IN_PROGRESS;
import static com.pmtracker.project_management_backend.task.TaskStatus.NEW;
import static com.pmtracker.project_management_backend.task.TaskStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Табличный список задач проекта: {@code GET /api/projects/{id}/tasks} (3.3).
 * <p>
 * Раньше эндпоинт отдавал все top-level задачи одним массивом, а фильтровала и сортировала их
 * страница на фронте. Теперь и то, и другое делает БД, и проверять это нужно именно на живой
 * Postgres: WHERE и ORDER BY собираются из параметров в строку JPQL (см. TaskRepositoryImpl),
 * поэтому опечатка в выражении или неподдерживаемая конструкция не видны ни компилятору, ни
 * тестам на моках — только настоящему запросу.
 * <p>
 * Порядок проверяется целиком, списком названий, а не «первая задача такая-то»: сортировка
 * ломается обычно не в начале, а на равных значениях и на пустых полях.
 */
class TaskListIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TimeLogRepository timeLogRepository;

    private User owner;
    private User member;
    private Project project;
    private String authHeader;

    @BeforeEach
    void createProject() {
        owner = saveUser("owner@example.com", "Яковлев", "Олег");
        member = saveUser("member@example.com", "Абрамов", "Игорь");

        project = new Project();
        project.setName("List project");
        project.setSlug("list-project");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        join(owner, ProjectRole.OWNER);
        join(member, ProjectRole.MEMBER);

        authHeader = "Bearer " + jwtService.generateAccessToken(owner);
    }

    // ----------------------------------------------------------------------- пагинация

    @Nested
    @DisplayName("пагинация")
    class Paging {

        @Test
        @DisplayName("страница отдаёт свой срез и общее количество задач")
        void returnsASliceAndTheTotal() throws Exception {
            for (int i = 1; i <= 7; i++) {
                task("T" + i).save();
            }

            mockMvc.perform(list("page=1&size=3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.pageSize").value(3))
                    .andExpect(jsonPath("$.totalItems").value(7))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.items.length()").value(3));

            assertThat(titles("page=1&size=3")).containsExactly("T4", "T5", "T6");
        }

        @Test
        @DisplayName("страница за концом списка — пусто, но totalItems прежний")
        void pastTheEndIsEmptyButStillCounts() throws Exception {
            task("Одна").save();

            mockMvc.perform(list("page=5&size=10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(1))
                    .andExpect(jsonPath("$.items.length()").value(0));
        }

        /**
         * Потолок нужен ровно затем, чтобы ?size=100000 не возвращал то, от чего пагинацию
         * и вводили; size=0 — это «параметр не передали», а не «пустая страница».
         */
        @Test
        @DisplayName("size за границами диапазона приводится к разрешённому")
        void clampsThePageSize() throws Exception {
            task("Одна").save();

            mockMvc.perform(list("size=0")).andExpect(jsonPath("$.pageSize").value(50));
            mockMvc.perform(list("size=100000")).andExpect(jsonPath("$.pageSize").value(200));
        }

        @Test
        @DisplayName("сортировка стабильна между страницами: тай-брейк по номеру задачи")
        void ordersDeterministicallyOnEqualValues() throws Exception {
            // Все задачи в одном статусе — по ключу сортировки они неразличимы, и без
            // тай-брейка одна и та же задача может попасть на обе страницы сразу.
            for (int i = 1; i <= 6; i++) {
                task("T" + i).status(NEW).save();
            }

            assertThat(titles("sort=STATUS&page=0&size=3")).containsExactly("T1", "T2", "T3");
            assertThat(titles("sort=STATUS&page=1&size=3")).containsExactly("T4", "T5", "T6");
        }
    }

    // ------------------------------------------------------------------------ фильтры

    @Nested
    @DisplayName("фильтры")
    class Filters {

        @Test
        @DisplayName("поиск по подстроке названия, без учёта регистра")
        void searchesTheTitleCaseInsensitively() throws Exception {
            task("Починить логин").save();
            task("ЛОГИН через SSO").save();
            task("Вёрстка футера").save();

            assertThat(titles("search=логин")).containsExactly("Починить логин", "ЛОГИН через SSO");
        }

        /**
         * Символы LIKE в пользовательском вводе — это не подстановочные знаки, а буквы:
         * человек, который ищет «50%», ждёт задачу про пятьдесят процентов, а не все задачи
         * подряд.
         */
        @Test
        @DisplayName("подстановочные знаки в запросе ищутся буквально")
        void treatsLikeWildcardsAsLiterals() throws Exception {
            task("Скидка 50% на всё").save();
            task("Обычная задача").save();

            assertThat(titles("search=50%")).containsExactly("Скидка 50% на всё");
            assertThat(titles("search=%")).containsExactly("Скидка 50% на всё");
            assertThat(titles("search=_")).isEmpty();
        }

        @Test
        @DisplayName("статус")
        void filtersByStatus() throws Exception {
            task("Новая").status(NEW).save();
            task("В работе").status(IN_PROGRESS).save();

            assertThat(titles("status=IN_PROGRESS")).containsExactly("В работе");
        }

        @Test
        @DisplayName("исполнитель и отдельно «без исполнителя»")
        void filtersByAssignee() throws Exception {
            task("Моя").assignee(owner).save();
            task("Чужая").assignee(member).save();
            task("Ничья").save();

            assertThat(titles("assigneeId=" + member.getId())).containsExactly("Чужая");
            assertThat(titles("unassigned=true")).containsExactly("Ничья");
            // unassigned важнее assigneeId: это выбранный пункт селекта, а не «фильтр не задан».
            assertThat(titles("unassigned=true&assigneeId=" + member.getId())).containsExactly("Ничья");
        }

        @Test
        @DisplayName("тег")
        void filtersByTag() throws Exception {
            Tag bug = tag("bug");
            task("С тегом").tag(bug).save();
            task("Без тега").save();

            assertThat(titles("tagId=" + bug.getId())).containsExactly("С тегом");
        }

        @Test
        @DisplayName("категория и отдельно «без категории»")
        void filtersByCategory() throws Exception {
            Category backend = category("Backend");
            task("С категорией").category(backend).save();
            task("Без категории").save();

            assertThat(titles("categoryId=" + backend.getId())).containsExactly("С категорией");
            assertThat(titles("uncategorized=true")).containsExactly("Без категории");
        }

        /**
         * «Мои задачи» (4.7) приезжают флагом, а не готовым id: сохранённое представление
         * «мои просроченные» уезжает по ссылке коллеге и обязано показать ему его задачи.
         * Проверяется это именно тем, что один и тот же запрос двум людям отвечает разным.
         */
        @Test
        @DisplayName("assignedToMe — задачи того, кто спрашивает, а не автора запроса")
        void filtersByTheViewer() throws Exception {
            task("Владельца").assignee(owner).save();
            task("Участника").assignee(member).save();
            task("Ничья").save();

            assertThat(titles("assignedToMe=true")).containsExactly("Владельца");

            String memberHeader = "Bearer " + jwtService.generateAccessToken(member);
            MvcResult asMember = mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks?assignedToMe=true")
                            .header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(asMember.getResponse().getContentAsString()).contains("Участника").doesNotContain("Владельца");
        }

        @Test
        @DisplayName("assignedToMe сильнее unassigned и assigneeId")
        void theViewerFilterWinsOverTheOthers() throws Exception {
            task("Владельца").assignee(owner).save();
            task("Участника").assignee(member).save();
            task("Ничья").save();

            assertThat(titles("assignedToMe=true&unassigned=true&assigneeId=" + member.getId()))
                    .containsExactly("Владельца");
        }

        @Test
        @DisplayName("фильтры складываются между собой")
        void combinesFilters() throws Exception {
            task("Логин падает").status(NEW).assignee(member).save();
            task("Логин медленный").status(DONE).assignee(member).save();
            task("Логин чужой").status(NEW).assignee(owner).save();

            assertThat(titles("search=логин&status=NEW&assigneeId=" + member.getId()))
                    .containsExactly("Логин падает");
        }

        @Test
        @DisplayName("без parentId в списке только top-level задачи, с parentId — подзадачи")
        void separatesTopLevelFromSubtasks() throws Exception {
            Task parent = task("Родитель").save();
            task("Подзадача").parent(parent).save();

            assertThat(titles("")).containsExactly("Родитель");
            assertThat(titles("parentId=" + parent.getId())).containsExactly("Подзадача");
        }

        @Test
        @DisplayName("родитель из чужого проекта — 400, а не выдача чужих задач")
        void rejectsAParentFromAnotherProject() throws Exception {
            Project other = new Project();
            other.setName("Other");
            other.setSlug("other-project");
            other.setCreatedBy(owner);
            projectRepository.save(other);

            Task foreign = new Task();
            foreign.setProject(other);
            foreign.setTaskNumber(projectRepository.reserveNextTaskNumber(other.getId()));
            foreign.setTitle("Чужая");
            foreign.setStatus(NEW);
            foreign.setUrgency(TaskUrgency.MEDIUM);
            foreign.setCreatedBy(owner);
            taskRepository.save(foreign);

            mockMvc.perform(list("parentId=" + foreign.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("PARENT_TASK_PROJECT_MISMATCH"));
        }
    }

    // ------------------------------------------------------------------ фильтр по сроку

    /**
     * Окна дедлайна (4.7): ради них и заведён фильтр — «мои просроченные» и «горит на этой
     * неделе» без него не выражаются никак. Проверяется здесь ровно то, что не видно из
     * кода: что окна вложены (просроченное входит в недельное), что закрытая задача из них
     * выпадает, и что «без срока» — это про пустое поле, а не про очередной отрезок.
     */
    @Nested
    @DisplayName("фильтр по сроку")
    class DueWindows {

        private final Instant now = Instant.now();

        @Test
        @DisplayName("OVERDUE — только те, у кого срок уже прошёл")
        void selectsOverdue() throws Exception {
            task("Вчера").dueDate(now.minus(1, ChronoUnit.DAYS)).save();
            task("Завтра").dueDate(now.plus(1, ChronoUnit.DAYS)).save();
            task("Без срока").save();

            assertThat(titles("due=OVERDUE")).containsExactly("Вчера");
        }

        @Test
        @DisplayName("окна вложены: просроченное горит и сегодня, и на этой неделе")
        void windowsIncludeWhatIsAlreadyOverdue() throws Exception {
            task("Вчера").dueDate(now.minus(1, ChronoUnit.DAYS)).save();
            task("Через час").dueDate(now.plus(1, ChronoUnit.HOURS)).save();
            task("Через три дня").dueDate(now.plus(3, ChronoUnit.DAYS)).save();
            task("Через месяц").dueDate(now.plus(30, ChronoUnit.DAYS)).save();

            assertThat(titles("due=TODAY")).containsExactly("Вчера", "Через час");
            assertThat(titles("due=WEEK")).containsExactly("Вчера", "Через час", "Через три дня");
        }

        /**
         * У выполненной задачи просроченный срок — это история, а не проблема: по тому же
         * списку статусов не шлются напоминания и не краснеет дата в таблице.
         */
        @Test
        @DisplayName("выполненные и отклонённые не горят")
        void ignoresClosedTasks() throws Exception {
            task("Активная").status(IN_PROGRESS).dueDate(now.minus(1, ChronoUnit.DAYS)).save();
            task("Выполненная").status(DONE).dueDate(now.minus(1, ChronoUnit.DAYS)).save();
            task("Отклонённая").status(REJECTED).dueDate(now.minus(1, ChronoUnit.DAYS)).save();

            assertThat(titles("due=OVERDUE")).containsExactly("Активная");
            assertThat(titles("due=WEEK")).containsExactly("Активная");
        }

        @Test
        @DisplayName("NONE — задачи без срока, независимо от статуса")
        void selectsTasksWithoutADueDate() throws Exception {
            task("Без срока").save();
            task("Закрытая без срока").status(DONE).save();
            task("Со сроком").dueDate(now.plus(1, ChronoUnit.DAYS)).save();

            assertThat(titles("due=NONE")).containsExactly("Без срока", "Закрытая без срока");
        }

        @Test
        @DisplayName("«мои просроченные» — это два фильтра, а не отдельный режим")
        void combinesWithTheViewerFilter() throws Exception {
            task("Моя просроченная").assignee(owner).dueDate(now.minus(2, ChronoUnit.DAYS)).save();
            task("Чужая просроченная").assignee(member).dueDate(now.minus(2, ChronoUnit.DAYS)).save();
            task("Моя будущая").assignee(owner).dueDate(now.plus(2, ChronoUnit.DAYS)).save();

            assertThat(titles("assignedToMe=true&due=OVERDUE")).containsExactly("Моя просроченная");
        }

        @Test
        @DisplayName("неизвестное окно — 400, а не молча выключенный фильтр")
        void rejectsAnUnknownWindow() throws Exception {
            mockMvc.perform(list("due=YESTERDAY")).andExpect(status().isBadRequest());
        }
    }

    // --------------------------------------------------------------------- сортировка

    @Nested
    @DisplayName("сортировка")
    class Sorting {

        @Test
        @DisplayName("по умолчанию — по номеру задачи по возрастанию")
        void defaultsToTaskNumber() throws Exception {
            task("Третья").save();
            task("Первая").save();
            task("Вторая").save();

            assertThat(titles("")).containsExactly("Третья", "Первая", "Вторая");
        }

        @Test
        @DisplayName("по названию — регистр не влияет на место в списке")
        void sortsByTitle() throws Exception {
            task("бета").save();
            task("Альфа").save();
            task("ГАММА").save();

            assertThat(titles("sort=TITLE")).containsExactly("Альфа", "бета", "ГАММА");
            assertThat(titles("sort=TITLE&descending=true")).containsExactly("ГАММА", "бета", "Альфа");
        }

        /**
         * Статус и срочность лежат в БД строками, поэтому «просто по столбцу» дало бы алфавит
         * (DONE раньше NEW). Нужен порядок жизненного цикла — тот же, в котором идут колонки
         * канбана.
         */
        @Test
        @DisplayName("по статусу — в порядке жизненного цикла, а не по алфавиту")
        void sortsByStatusLifecycleOrder() throws Exception {
            task("готова").status(DONE).save();
            task("новая").status(NEW).save();
            task("отклонена").status(REJECTED).save();
            task("в работе").status(IN_PROGRESS).save();

            assertThat(titles("sort=STATUS")).containsExactly("новая", "в работе", "готова", "отклонена");
        }

        @Test
        @DisplayName("по срочности — от менее важной к более важной")
        void sortsByUrgency() throws Exception {
            task("срочная").urgency(TaskUrgency.URGENT).save();
            task("низкая").urgency(TaskUrgency.LOW).save();
            task("высокая").urgency(TaskUrgency.HIGH).save();

            assertThat(titles("sort=URGENCY")).containsExactly("низкая", "высокая", "срочная");
            assertThat(titles("sort=URGENCY&descending=true")).containsExactly("срочная", "высокая", "низкая");
        }

        @Test
        @DisplayName("по исполнителю — по фамилии, затем по имени")
        void sortsByAssigneeName() throws Exception {
            User sameSurname = saveUser("boris.abramov@example.com", "Абрамов", "Борис");
            join(sameSurname, ProjectRole.MEMBER);

            task("к Яковлеву").assignee(owner).save();
            task("к Борису").assignee(sameSurname).save();
            task("к Игорю").assignee(member).save();

            assertThat(titles("sort=ASSIGNEE")).containsExactly("к Борису", "к Игорю", "к Яковлеву");
        }

        /**
         * Пустое значение уезжает в конец при любом направлении. Иначе при сортировке по
         * убыванию наверх всплывает десяток прочерков и прячет то, ради чего сортировали.
         */
        @Test
        @DisplayName("задачи без исполнителя — в конце в обе стороны")
        void keepsUnassignedTasksLast() throws Exception {
            task("ничья").save();
            task("Яковлеву").assignee(owner).save();
            task("Абрамову").assignee(member).save();

            assertThat(titles("sort=ASSIGNEE")).containsExactly("Абрамову", "Яковлеву", "ничья");
            assertThat(titles("sort=ASSIGNEE&descending=true")).containsExactly("Яковлеву", "Абрамову", "ничья");
        }

        @Test
        @DisplayName("по сроку — задачи без срока в конце в обе стороны")
        void sortsByDueDateKeepingUndatedLast() throws Exception {
            Instant now = Instant.now();
            task("без срока").save();
            task("послезавтра").dueDate(now.plus(2, ChronoUnit.DAYS)).save();
            task("завтра").dueDate(now.plus(1, ChronoUnit.DAYS)).save();

            assertThat(titles("sort=DUE_DATE")).containsExactly("завтра", "послезавтра", "без срока");
            assertThat(titles("sort=DUE_DATE&descending=true"))
                    .containsExactly("послезавтра", "завтра", "без срока");
        }

        @Test
        @DisplayName("по тегу — задачи без тега в конце")
        void sortsByTag() throws Exception {
            task("без тега").save();
            task("с багом").tag(tag("bug")).save();
            task("с фичей").tag(tag("feature")).save();

            assertThat(titles("sort=TAG")).containsExactly("с багом", "с фичей", "без тега");
        }

        @Test
        @DisplayName("по категории — задачи без категории в конце")
        void sortsByCategory() throws Exception {
            task("без категории").save();
            task("бэкенд").category(category("Backend")).save();
            task("фронтенд").category(category("Frontend")).save();

            assertThat(titles("sort=CATEGORY")).containsExactly("бэкенд", "фронтенд", "без категории");
        }

        @Test
        @DisplayName("по списанным часам — сумма по time_logs, ноль у задач без списаний")
        void sortsBySpentHours() throws Exception {
            Task heavy = task("много").save();
            Task light = task("мало").save();
            task("нисколько").save();

            logHours(heavy, "3.5");
            logHours(heavy, "2.0");
            logHours(light, "1.0");

            assertThat(titles("sort=HOURS&descending=true")).containsExactly("много", "мало", "нисколько");
            assertThat(titles("sort=HOURS")).containsExactly("нисколько", "мало", "много");
        }

        @Test
        @DisplayName("несуществующий ключ сортировки — 400, а не тихий порядок по умолчанию")
        void rejectsAnUnknownSortKey() throws Exception {
            task("Одна").save();

            mockMvc.perform(list("sort=DROP_TABLE")).andExpect(status().isBadRequest());
        }
    }

    // -------------------------------------------------------------------------- доска

    @Nested
    @DisplayName("доска")
    class Board {

        @Test
        @DisplayName("отдаёт все top-level задачи массивом, в порядке position")
        void returnsEveryTopLevelTaskInPositionOrder() throws Exception {
            Task parent = task("Вторая").position(1).save();
            task("Первая").position(0).save();
            task("Подзадача").parent(parent).position(0).save();

            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks/board")
                            .header(AUTHORIZATION, authHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].title").value("Первая"))
                    .andExpect(jsonPath("$[1].title").value("Вторая"));
        }
    }

    // ------------------------------------------------------------------------ хелперы

    /**
     * Строка запроса уходит в MockMvc как есть: он разбирает её на параметры, но не
     * раскодирует percent-encoding, поэтому «%» и «_» здесь пишутся буквально — как раз
     * то, что нужно тесту про подстановочные знаки.
     */
    private MockHttpServletRequestBuilder list(String queryString) {
        String url = "/api/projects/" + project.getId() + "/tasks";
        return get(queryString.isEmpty() ? url : url + "?" + queryString).header(AUTHORIZATION, authHeader);
    }

    /**
     * Названия задач страницы в том порядке, в котором их вернул сервер. Регулярка по телу,
     * а не разбор JSON: единственное, что нужно этим тестам, — порядок, и вытащить его
     * из готовой строки короче, чем заводить маппер ради одного поля.
     */
    private static final Pattern TITLE = Pattern.compile("\"title\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private List<String> titles(String queryString) throws Exception {
        MvcResult result = mockMvc.perform(list(queryString)).andExpect(status().isOk()).andReturn();
        Matcher matcher = TITLE.matcher(result.getResponse().getContentAsString());
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            titles.add(matcher.group(1));
        }
        return titles;
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

    private void join(User user, ProjectRole role) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }

    private Tag tag(String name) {
        Tag tag = new Tag();
        tag.setProject(project);
        tag.setName(name);
        tag.setColor("#888888");
        tag.setCreatedBy(owner);
        return tagRepository.save(tag);
    }

    private Category category(String name) {
        Category category = new Category();
        category.setProject(project);
        category.setName(name);
        category.setCreatedBy(owner);
        return categoryRepository.save(category);
    }

    private void logHours(Task task, String hours) {
        TimeLog timeLog = new TimeLog();
        timeLog.setTask(task);
        timeLog.setUser(owner);
        timeLog.setHours(new BigDecimal(hours));
        timeLog.setSpentOn(LocalDate.now());
        timeLogRepository.save(timeLog);
    }

    private TaskBuilder task(String title) {
        return new TaskBuilder(title);
    }

    /**
     * Задачи здесь заводятся напрямую, а не через API: тестам нужны конкретные комбинации
     * пустых и заполненных полей, а форма создания часть из них проставляет сама.
     */
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

        TaskBuilder urgency(TaskUrgency urgency) {
            task.setUrgency(urgency);
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

        TaskBuilder category(Category category) {
            task.setCategory(category);
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

        TaskBuilder position(int position) {
            task.setPosition(position);
            return this;
        }

        Task save() {
            task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
            return taskRepository.save(task);
        }
    }
}
