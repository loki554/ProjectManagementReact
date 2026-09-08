package com.pmtracker.project_management_backend.export;

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
import com.pmtracker.project_management_backend.sprint.Sprint;
import com.pmtracker.project_management_backend.sprint.SprintRepository;
import com.pmtracker.project_management_backend.sprint.SprintStatus;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.tag.TagRepository;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import com.pmtracker.project_management_backend.timelog.TimeLog;
import com.pmtracker.project_management_backend.timelog.TimeLogRepository;
import com.pmtracker.project_management_backend.wiki.ProjectWiki;
import com.pmtracker.project_management_backend.wiki.ProjectWikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import static com.pmtracker.project_management_backend.task.TaskStatus.IN_PROGRESS;
import static com.pmtracker.project_management_backend.task.TaskStatus.NEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Выгрузка данных проекта (4.12): {@code /api/projects/{id}/export/tasks.csv},
 * {@code .../tasks.json} и {@code .../wiki.md}.
 *
 * <p>Пункт ничего не пишет, поэтому проверять здесь стоит не «сохранилось ли», а ровно те
 * решения, которые легко нарушить незаметно: что подзадачи в файл попадают (на экране их
 * не видно, и потерять их можно молча), что задача из корзины — нет, что название и
 * описание, начинающиеся с «=», не приезжают в Excel формулой, и что вики выгружается
 * байт в байт — без BOM и без дописанного заголовка.
 */
class ExportIntegrationTest extends IntegrationTest {

    /**
     * Признак кодировки для Excel — байтами, а не символом в исходнике: BOM невидим, и
     * тест, из которого его случайно вырезали, продолжал бы выглядеть осмысленно. Проверять
     * его на уровне байтов вдобавок правильнее по сути: BOM — это решение о том, что уедет
     * в файл, а не о том, что увидит разборщик строки.
     */
    private static final byte[] BOM_BYTES = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TimeLogRepository timeLogRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private SprintRepository sprintRepository;
    @Autowired private ProjectWikiRepository projectWikiRepository;

    private User owner;
    private User member;
    private User viewer;
    private User outsider;
    private Project project;
    private String ownerHeader;
    private String viewerHeader;
    private String outsiderHeader;

    @BeforeEach
    void createProject() {
        owner = saveUser("owner@example.com", "Яковлев", "Олег");
        member = saveUser("member@example.com", "Петров", "Пётр");
        viewer = saveUser("viewer@example.com", "Абрамов", "Игорь");
        outsider = saveUser("outsider@example.com", "Чужой", "Сергей");

        project = saveProject();
        join(owner, ProjectRole.OWNER);
        join(member, ProjectRole.MEMBER);
        join(viewer, ProjectRole.VIEWER);

        ownerHeader = "Bearer " + jwtService.generateAccessToken(owner);
        viewerHeader = "Bearer " + jwtService.generateAccessToken(viewer);
        outsiderHeader = "Bearer " + jwtService.generateAccessToken(outsider);
    }

    // ---------------------------------------------------------------------- задачи в CSV

    @Nested
    @DisplayName("задачи в CSV")
    class TasksCsv {

        @Test
        @DisplayName("отдаёт BOM, заголовок и строку задачи со всеми её связями")
        void writesRows() throws Exception {
            Task task = task("Починить логин", IN_PROGRESS);
            task.setAssignee(member);
            task.setUrgency(TaskUrgency.HIGH);
            task.setDescription("Разобраться с токенами");
            task.setTag(tag("Баг"));
            task.setCategory(category("Бэкенд"));
            task.setSprint(sprint("Сентябрь"));
            task.setDueDate(Instant.parse("2026-09-30T12:00:00Z"));
            taskRepository.save(task);
            logTime(task, member, "2.50");

            MvcResult result = mockMvc.perform(get("/api/projects/{id}/export/tasks.csv", project.getId())
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/csv"))
                    .andExpect(header().string(CONTENT_DISPOSITION,
                            containsString("tasks-export-project-" + LocalDate.now() + ".csv")))
                    .andReturn();

            byte[] body = result.getResponse().getContentAsByteArray();
            String csv = new String(body, StandardCharsets.UTF_8);

            // BOM — ради Excel: без него он читает UTF-8 как ANSI, и кириллица превращается в кашу.
            assertThat(body).startsWith(BOM_BYTES);
            assertThat(csv).contains("Номер,Родитель,Название,Статус,Срочность,Исполнитель,"
                    + "Email исполнителя,Автор,Категория,Тэг,Спринт,Срок,Часы,Создана,Обновлена,Описание");
            // Статус и срочность — кодами, а не подписями с экрана: подписи живут в i18n
            // фронтенда, и вторая их копия на сервере разошлась бы с первой.
            assertThat(csv).contains(task.getTaskNumber() + ",,Починить логин,IN_PROGRESS,HIGH,"
                    + "Петров Пётр,member@example.com,Яковлев Олег,Бэкенд,Баг,Сентябрь,"
                    + "2026-09-30T12:00:00Z,2.50,");
            assertThat(csv).endsWith("Разобраться с токенами\r\n");
        }

        /**
         * Подзадачи на экране спрятаны под родителем, и потерять их в выгрузке можно молча —
         * а файл просят как раз затем, чтобы забрать всё. Дерево при этом несёт колонка
         * «Родитель», а не порядок строк.
         */
        @Test
        @DisplayName("выгружает подзадачи наравне с задачами верхнего уровня")
        void includesSubtasks() throws Exception {
            Task parent = task("Родитель", NEW);
            Task child = task("Подзадача", NEW);
            child.setParentTask(parent);
            taskRepository.save(child);

            String csv = csv(ownerHeader);

            assertThat(csv).contains("\r\n" + parent.getTaskNumber() + ",,Родитель,");
            assertThat(csv).contains("\r\n" + child.getTaskNumber() + ",#" + parent.getTaskNumber()
                    + ",Подзадача,");
        }

        /**
         * Трекер везде считает задачу в корзине несуществующей — доска, список, счётчик
         * спринта, отчёт по времени. Выгрузка, единственная на всё приложение, не должна
         * считать иначе: иначе восстановленная задача приехала бы в файл дважды.
         */
        @Test
        @DisplayName("задача из корзины в файл не попадает")
        void skipsTrashedTasks() throws Exception {
            Task kept = task("Живая", NEW);
            Task trashed = task("Удалённая", NEW);
            mockMvc.perform(delete("/api/tasks/{id}", trashed.getId()).header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isNoContent());

            String csv = csv(ownerHeader);

            assertThat(csv).contains("Живая");
            assertThat(csv).doesNotContain("Удалённая");
            assertThat(csv).contains("\r\n" + kept.getTaskNumber() + ",");
        }

        /**
         * Название и описание задачи пишет любой участник проекта, а ячейка, начинающаяся
         * с «=», в Excel и Sheets становится формулой — то есть выгрузка превращается в
         * способ выполнить чужой код на чужой машине.
         */
        @Test
        @DisplayName("ячейка-формула обезвреживается апострофом")
        void neutralizesFormulas() throws Exception {
            Task task = task("=cmd|'/c calc'!A1", NEW);
            task.setDescription("=1+1");
            taskRepository.save(task);

            String csv = csv(ownerHeader);

            assertThat(csv).contains(",'=1+1");
            assertThat(csv).contains("'=cmd");
            assertThat(csv).doesNotContain(",=1+1");
        }

        @Test
        @DisplayName("перевод строки и кавычки в описании экранируются по RFC 4180")
        void quotesSpecialCharacters() throws Exception {
            Task task = task("Задача", NEW);
            task.setDescription("Смотрел \"логи\", потом\nправил");
            taskRepository.save(task);

            assertThat(csv(ownerHeader)).contains("\"Смотрел \"\"логи\"\", потом\nправил\"");
        }

        @Test
        @DisplayName("проект без задач — файл с одним заголовком")
        void writesHeaderOnlyForEmptyProject() throws Exception {
            String csv = csv(ownerHeader);

            assertThat(csv).contains("Номер,Родитель,Название,");
            assertThat(csv.lines().count()).isEqualTo(1);
        }
    }

    // --------------------------------------------------------------------- задачи в JSON

    @Nested
    @DisplayName("задачи в JSON")
    class TasksJson {

        @Test
        @DisplayName("отдаёт шапку с проектом, счётчиком и задачами со структурой")
        void writesPayload() throws Exception {
            Task task = task("Починить логин", IN_PROGRESS);
            task.setAssignee(member);
            taskRepository.save(task);
            logTime(task, member, "2.50");

            mockMvc.perform(get("/api/projects/{id}/export/tasks.json", project.getId())
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(header().string(CONTENT_DISPOSITION,
                            containsString("tasks-export-project-" + LocalDate.now() + ".json")))
                    .andExpect(jsonPath("$.exportedAt").isNotEmpty())
                    .andExpect(jsonPath("$.project.slug").value("export-project"))
                    .andExpect(jsonPath("$.taskCount").value(1))
                    .andExpect(jsonPath("$.tasks", hasSize(1)))
                    .andExpect(jsonPath("$.tasks[0].taskNumber").value(task.getTaskNumber()))
                    .andExpect(jsonPath("$.tasks[0].status").value("IN_PROGRESS"))
                    // Исполнитель — объект, а не склеенная строка: тому, кто разбирает файл
                    // скриптом, нужен email, а не «Петров Пётр», которое пришлось бы делить
                    // обратно.
                    .andExpect(jsonPath("$.tasks[0].assignee.email").value("member@example.com"))
                    .andExpect(jsonPath("$.tasks[0].hoursSpent").value(2.50))
                    // Пустая ссылка — null, а не пустая строка: в CSV их не различить, и
                    // ровно за этим различением в JSON и приходят.
                    .andExpect(jsonPath("$.tasks[0].tag").value(nullValue()))
                    .andExpect(jsonPath("$.tasks[0].sprint").value(nullValue()))
                    .andExpect(jsonPath("$.tasks[0].parentTaskNumber").value(nullValue()));
        }

        /**
         * Форма выгрузки — своя, а не TaskResponse: position, version и openBlockerCount это
         * механика экрана, и файлу, который кладут в архив, они не нужны.
         */
        @Test
        @DisplayName("не выносит наружу механику экрана — position, version, блокеры")
        void omitsScreenMechanics() throws Exception {
            task("Задача", NEW);

            mockMvc.perform(get("/api/projects/{id}/export/tasks.json", project.getId())
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tasks[0].position").doesNotExist())
                    .andExpect(jsonPath("$.tasks[0].version").doesNotExist())
                    .andExpect(jsonPath("$.tasks[0].openBlockerCount").doesNotExist());
        }

        @Test
        @DisplayName("проект без задач — пустой массив и нулевой счётчик")
        void writesEmptyPayload() throws Exception {
            mockMvc.perform(get("/api/projects/{id}/export/tasks.json", project.getId())
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskCount").value(0))
                    .andExpect(jsonPath("$.tasks", hasSize(0)));
        }
    }

    // ------------------------------------------------------------------- вики в Markdown

    @Nested
    @DisplayName("вики в Markdown")
    class WikiMarkdown {

        /**
         * Байт в байт: вики уже Markdown, и файл обязан открыться тем же текстом, который
         * человек видел в редакторе. BOM здесь, в отличие от CSV, лишний — из-за него
         * «# Заголовок» первой строкой перестал бы быть заголовком.
         */
        @Test
        @DisplayName("отдаёт содержимое как есть, без BOM и без дописанного заголовка")
        void writesContentAsIs() throws Exception {
            saveWiki("# Регламент\n\nПервый абзац.\n");

            MvcResult result = mockMvc.perform(get("/api/projects/{id}/export/wiki.md", project.getId())
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                    .andExpect(header().string(CONTENT_DISPOSITION,
                            containsString("wiki-export-project-" + LocalDate.now() + ".md")))
                    .andReturn();

            byte[] body = result.getResponse().getContentAsByteArray();

            // Точное равенство, а не «содержит»: оно же доказывает, что ничего не дописано
            // ни в начало (BOM, заголовок с именем проекта), ни в конец.
            assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("# Регламент\n\nПервый абзац.\n");
        }

        @Test
        @DisplayName("у проекта без вики — пустой файл, а не 404")
        void writesEmptyFileWithoutWiki() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/projects/{id}/export/wiki.md", project.getId())
                            .header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        }
    }

    // -------------------------------------------------------------------------- права

    @Nested
    @DisplayName("права")
    class Access {

        /**
         * VIEWER выгружает наравне со всеми: файл не показывает ничего, чего не видно на
         * доске, в списке задач и на странице вики.
         */
        @Test
        @DisplayName("VIEWER выгружает все три файла")
        void viewerMayExport() throws Exception {
            task("Задача", NEW);

            mockMvc.perform(get("/api/projects/{id}/export/tasks.csv", project.getId())
                    .header(AUTHORIZATION, viewerHeader)).andExpect(status().isOk());
            mockMvc.perform(get("/api/projects/{id}/export/tasks.json", project.getId())
                    .header(AUTHORIZATION, viewerHeader)).andExpect(status().isOk());
            mockMvc.perform(get("/api/projects/{id}/export/wiki.md", project.getId())
                    .header(AUTHORIZATION, viewerHeader)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("посторонний не выгружает чужой проект")
        void outsiderIsRejected() throws Exception {
            mockMvc.perform(get("/api/projects/{id}/export/tasks.csv", project.getId())
                    .header(AUTHORIZATION, outsiderHeader)).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/projects/{id}/export/tasks.json", project.getId())
                    .header(AUTHORIZATION, outsiderHeader)).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/projects/{id}/export/wiki.md", project.getId())
                    .header(AUTHORIZATION, outsiderHeader)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("без токена — 401")
        void anonymousIsRejected() throws Exception {
            mockMvc.perform(get("/api/projects/{id}/export/tasks.csv", project.getId()))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------------------- фикстуры

    private String csv(String authHeader) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/export/tasks.csv", project.getId())
                        .header(AUTHORIZATION, authHeader))
                .andExpect(status().isOk())
                .andReturn();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
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

    private Project saveProject() {
        Project newProject = new Project();
        newProject.setName("Export project");
        newProject.setSlug("export-project");
        newProject.setCreatedBy(owner);
        return projectRepository.save(newProject);
    }

    private void join(User user, ProjectRole role) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }

    private Task task(String title, TaskStatus status) {
        Task task = new Task();
        task.setProject(project);
        task.setTitle(title);
        task.setStatus(status);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setPosition(0);
        task.setCreatedBy(owner);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        return taskRepository.save(task);
    }

    private Tag tag(String name) {
        Tag tag = new Tag();
        tag.setProject(project);
        tag.setName(name);
        tag.setColor("#ff0000");
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

    private Sprint sprint(String name) {
        Sprint sprint = new Sprint();
        sprint.setProject(project);
        sprint.setName(name);
        sprint.setStartDate(LocalDate.of(2026, 9, 1));
        sprint.setEndDate(LocalDate.of(2026, 9, 30));
        sprint.setStatus(SprintStatus.PLANNED);
        sprint.setCreatedBy(owner);
        return sprintRepository.save(sprint);
    }

    private void logTime(Task task, User user, String hours) {
        TimeLog log = new TimeLog();
        log.setTask(task);
        log.setUser(user);
        log.setHours(new BigDecimal(hours));
        log.setSpentOn(LocalDate.of(2026, 9, 1));
        timeLogRepository.save(log);
    }

    private void saveWiki(String content) {
        ProjectWiki wiki = new ProjectWiki();
        wiki.setProject(project);
        wiki.setContent(content);
        wiki.setUpdatedBy(owner);
        projectWikiRepository.save(wiki);
    }
}
