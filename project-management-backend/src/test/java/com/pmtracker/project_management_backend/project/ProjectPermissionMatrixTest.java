package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.attachment.Attachment;
import com.pmtracker.project_management_backend.attachment.AttachmentRepository;
import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.category.Category;
import com.pmtracker.project_management_backend.category.CategoryRepository;
import com.pmtracker.project_management_backend.comment.TaskComment;
import com.pmtracker.project_management_backend.comment.TaskCommentRepository;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.tag.TagRepository;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import com.pmtracker.project_management_backend.timelog.TimeLog;
import com.pmtracker.project_management_backend.timelog.TimeLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.pmtracker.project_management_backend.project.ProjectPermissionMatrixTest.Access.ALLOWED;
import static com.pmtracker.project_management_backend.project.ProjectPermissionMatrixTest.Access.INSUFFICIENT_ROLE;
import static com.pmtracker.project_management_backend.project.ProjectPermissionMatrixTest.Access.NOT_ATTACHMENT_OWNER;
import static com.pmtracker.project_management_backend.project.ProjectPermissionMatrixTest.Access.NOT_COMMENT_OWNER;
import static com.pmtracker.project_management_backend.project.ProjectPermissionMatrixTest.Access.NOT_TIME_LOG_OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Матрица прав: каждый эндпоинт, привязанный к проекту, × каждая роль участника.
 * <p>
 * Права здесь держатся исключительно на дисциплине: в сервисе руками зовётся
 * {@code requireMembership} и, где нужно, {@code requireRole}. Забыть второй вызов в новом
 * методе ничего не стоит, и потеря будет незаметна — эндпоинт продолжит работать, просто
 * станет доступен всем подряд. Таблица {@link #ENDPOINTS} ниже — единственное место, где это
 * ловится: она одновременно тест и документация того, кому что можно.
 * <p>
 * Что проверяется на каждой строке: разрешено — любой 2xx (какой именно код возвращает
 * эндпоинт — не вопрос прав), запрещено — 403 с конкретным кодом ошибки. Код важен: 403
 * «не хватает роли» и 403 «это не твой комментарий» приходят из разных проверок, и подмена
 * одной другой означала бы, что сработала не та защита.
 * <p>
 * В таблице только то, что относится к проекту. {@code POST /api/projects} (создать проект
 * может любой), {@code /api/users/**}, {@code /api/notifications/**}, {@code /api/tasks/mine}
 * и {@code /api/auth/**} ролей проекта не знают вовсе, и их здесь нет.
 */
class ProjectPermissionMatrixTest extends IntegrationTest {

    /** Ожидаемый исход обращения к эндпоинту. Для запрета — ещё и код ошибки в теле 403. */
    enum Access {
        ALLOWED(null),
        INSUFFICIENT_ROLE("INSUFFICIENT_ROLE"),
        NOT_COMMENT_OWNER("NOT_COMMENT_OWNER"),
        NOT_TIME_LOG_OWNER("NOT_TIME_LOG_OWNER"),
        NOT_ATTACHMENT_OWNER("NOT_ATTACHMENT_OWNER");

        private final String errorCode;

        Access(String errorCode) {
            this.errorCode = errorCode;
        }
    }

    /**
     * Строка таблицы: как позвать эндпоинт (ссылки на id берутся из фикстуры, она пересоздаётся
     * перед каждым запуском) и что должна получить каждая из четырёх ролей.
     */
    private record Endpoint(String name,
                            Function<Fixture, AbstractMockHttpServletRequestBuilder<?>> request,
                            Map<ProjectRole, Access> expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final String NEW_TASK_BODY = """
            {"title":"A new task","status":"NEW","urgency":"MEDIUM"}""";

    /** Настоящий PNG: превью проходит через ImageSanitizer, который его декодирует. */
    private static final byte[] PNG_BYTES = pngBytes();

    // ------------------------------------------------------------------- ТАБЛИЦА ПРАВ

    private static final List<Endpoint> ENDPOINTS = List.of(

            // ---- Чтение: открыто всем участникам, включая VIEWER (таблица ролей §5 плана) ----
            //       эндпоинт                                    запрос                                   OWNER    ADMIN    MEMBER   VIEWER
            endpoint("GET    /projects/{id}",                    f -> get("/api/projects/" + f.projectId),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /projects/slug/{slug}",             f -> get("/api/projects/slug/" + f.projectSlug),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /projects/{id}/preview-image",      f -> get("/api/projects/" + f.projectId + "/preview-image"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /projects/{id}/members",            f -> get("/api/projects/" + f.projectId + "/members"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /projects/{id}/tasks",              f -> get("/api/projects/" + f.projectId + "/tasks"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /projects/{id}/tasks/by-number/{n}", f -> get("/api/projects/" + f.projectId
                            + "/tasks/by-number/" + f.taskNumber),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /projects/{id}/categories",         f -> get("/api/projects/" + f.projectId + "/categories"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /projects/{id}/tags",               f -> get("/api/projects/" + f.projectId + "/tags"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /projects/{id}/wiki",               f -> get("/api/projects/" + f.projectId + "/wiki"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /projects/{id}/activity",           f -> get("/api/projects/" + f.projectId + "/activity"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /projects/{id}/star",               f -> get("/api/projects/" + f.projectId + "/star"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /tasks/{id}",                       f -> get("/api/tasks/" + f.taskId),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /tasks/{id}/subtasks",              f -> get("/api/tasks/" + f.taskId + "/subtasks"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /tasks/{id}/comments",              f -> get("/api/tasks/" + f.taskId + "/comments"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /tasks/{id}/time-logs",             f -> get("/api/tasks/" + f.taskId + "/time-logs"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /tasks/{id}/attachments",           f -> get("/api/tasks/" + f.taskId + "/attachments"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("GET    /attachments/{id}/download",        f -> get("/api/attachments/" + f.attachmentId + "/download"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),

            // ---- Настройки проекта: только OWNER (§5, «Настройки/архивация/удаление проекта») ----
            endpoint("PATCH  /projects/{id}",                    f -> patch("/api/projects/" + f.projectId)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Renamed project","description":"d","archived":false}"""),
                    ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),
            endpoint("DELETE /projects/{id}",                    f -> delete("/api/projects/" + f.projectId),
                    ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),
            endpoint("POST   /projects/{id}/preview-image",      f -> multipart("/api/projects/" + f.projectId + "/preview-image")
                            .file(new MockMultipartFile("file", "preview.png", "image/png", PNG_BYTES)),
                    ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),

            // ---- Справочники проекта: тоже часть настроек, тоже только OWNER ----
            endpoint("POST   /projects/{id}/categories",         f -> post("/api/projects/" + f.projectId + "/categories")
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Another category"}"""),
                    ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),
            endpoint("PATCH  /categories/{id}",                  f -> patch("/api/categories/" + f.categoryId)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Renamed category"}"""),
                    ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),
            endpoint("DELETE /categories/{id}",                  f -> delete("/api/categories/" + f.categoryId),
                    ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),
            endpoint("POST   /projects/{id}/tags",               f -> post("/api/projects/" + f.projectId + "/tags")
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"another-tag","color":"#112233"}"""),
                    ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),
            endpoint("PATCH  /tags/{id}",                        f -> patch("/api/tags/" + f.tagId)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"renamed-tag","color":"#445566"}"""),
                    ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),
            endpoint("DELETE /tags/{id}",                        f -> delete("/api/tags/" + f.tagId),
                    ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),

            // ---- Участники и роли: OWNER и ADMIN (§5, «Управление участниками/ролями») ----
            endpoint("POST   /projects/{id}/members",            f -> post("/api/projects/" + f.projectId + "/members")
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"email":"%s","role":"MEMBER"}""".formatted(f.outsiderEmail)),
                    ALLOWED, ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),
            endpoint("PATCH  /projects/{id}/members/{userId}",   f -> patch("/api/projects/" + f.projectId
                            + "/members/" + f.viewerUserId)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"role":"MEMBER"}"""),
                    ALLOWED, ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),
            endpoint("DELETE /projects/{id}/members/{userId}",   f -> delete("/api/projects/" + f.projectId
                            + "/members/" + f.viewerUserId),
                    ALLOWED, ALLOWED, INSUFFICIENT_ROLE, INSUFFICIENT_ROLE),

            // ---- Работа по проекту: MEMBER и выше, но не VIEWER (§5) ----
            endpoint("PUT    /projects/{id}/wiki",               f -> put("/api/projects/" + f.projectId + "/wiki")
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"content":"# Wiki"}"""),
                    ALLOWED, ALLOWED, ALLOWED, INSUFFICIENT_ROLE),
            endpoint("POST   /projects/{id}/tasks",              f -> post("/api/projects/" + f.projectId + "/tasks")
                            .contentType(APPLICATION_JSON)
                            .content(NEW_TASK_BODY),
                    ALLOWED, ALLOWED, ALLOWED, INSUFFICIENT_ROLE),
            endpoint("PATCH  /tasks/{id}",                       f -> patch("/api/tasks/" + f.taskId)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Renamed task","status":"NEW","urgency":"MEDIUM"}"""),
                    ALLOWED, ALLOWED, ALLOWED, INSUFFICIENT_ROLE),
            endpoint("PATCH  /tasks/{id}/status",                f -> patch("/api/tasks/" + f.taskId + "/status")
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"status":"IN_PROGRESS","position":0,"expectedStatus":"NEW"}"""),
                    ALLOWED, ALLOWED, ALLOWED, INSUFFICIENT_ROLE),
            endpoint("DELETE /tasks/{id}",                       f -> delete("/api/tasks/" + f.taskId),
                    ALLOWED, ALLOWED, ALLOWED, INSUFFICIENT_ROLE),
            endpoint("POST   /tasks/{id}/subtasks",              f -> post("/api/tasks/" + f.taskId + "/subtasks")
                            .contentType(APPLICATION_JSON)
                            .content(NEW_TASK_BODY),
                    ALLOWED, ALLOWED, ALLOWED, INSUFFICIENT_ROLE),
            endpoint("POST   /tasks/{id}/comments",              f -> post("/api/tasks/" + f.taskId + "/comments")
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"body":"a comment"}"""),
                    ALLOWED, ALLOWED, ALLOWED, INSUFFICIENT_ROLE),
            endpoint("POST   /tasks/{id}/time-logs",             f -> post("/api/tasks/" + f.taskId + "/time-logs")
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"hours":1.5,"spentOn":"%s"}""".formatted(LocalDate.now())),
                    ALLOWED, ALLOWED, ALLOWED, INSUFFICIENT_ROLE),
            endpoint("POST   /tasks/{id}/attachments",           f -> multipart("/api/tasks/" + f.taskId + "/attachments")
                            .file(new MockMultipartFile("file", "note.txt", "text/plain",
                                    "plain text".getBytes(StandardCharsets.UTF_8))),
                    ALLOWED, ALLOWED, ALLOWED, INSUFFICIENT_ROLE),

            // ---- Чужие комментарий/запись времени/вложение: нужна роль MEMBER И авторство, ----
            // ---- либо модераторские ADMIN/OWNER. Все три сделаны по одному образцу.        ----
            endpoint("DELETE /comments/{id} (чужой)",            f -> delete("/api/comments/" + f.commentId),
                    ALLOWED, ALLOWED, NOT_COMMENT_OWNER, INSUFFICIENT_ROLE),
            endpoint("DELETE /time-logs/{id} (чужой)",           f -> delete("/api/time-logs/" + f.timeLogId),
                    ALLOWED, ALLOWED, NOT_TIME_LOG_OWNER, INSUFFICIENT_ROLE),
            endpoint("DELETE /attachments/{id} (чужой)",         f -> delete("/api/attachments/" + f.attachmentId),
                    ALLOWED, ALLOWED, NOT_ATTACHMENT_OWNER, INSUFFICIENT_ROLE),

            // ---- Звёздочка: личная отметка участника, роль не при чём ----
            endpoint("PUT    /projects/{id}/star",               f -> put("/api/projects/" + f.projectId + "/star"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED),
            endpoint("DELETE /projects/{id}/star",               f -> delete("/api/projects/" + f.projectId + "/star"),
                    ALLOWED, ALLOWED, ALLOWED, ALLOWED));

    // --------------------------------------------------------------------------- тесты

    @ParameterizedTest(name = "{1} → {0}")
    @MethodSource("roleMatrix")
    @DisplayName("роль × эндпоинт → ожидаемый доступ")
    void rolesGetExactlyTheAccessTheMatrixSays(Endpoint endpoint, ProjectRole role, Access expected) throws Exception {
        ResultActions result = call(endpoint, tokenOf(role));

        if (expected == ALLOWED) {
            result.andExpect(status().is2xxSuccessful());
        } else {
            result.andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(expected.errorCode));
        }
    }

    /**
     * Отдельный столбец матрицы, вынесенный в свой тест: он одинаков для всех строк, и
     * повторять его 43 раза в таблице значило бы утопить в нём саму таблицу. Смысл при этом
     * ровно тот же — посторонний не должен получить 2xx ни от одного эндпоинта проекта,
     * включая чтение.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allEndpoints")
    @DisplayName("посторонний не участник — 403 NOT_A_PROJECT_MEMBER на всём")
    void nonMemberIsRefusedEverywhere(Endpoint endpoint) throws Exception {
        call(endpoint, outsiderToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_A_PROJECT_MEMBER"));
    }

    /**
     * Второй общий столбец: без токена не открывается ничего. Ловит эндпоинт, случайно
     * попавший в permitAll (см. SecurityConfig) — проверка ролей в сервисе такую ошибку
     * не поймает, там просто не окажется пользователя.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allEndpoints")
    @DisplayName("без токена — 401 UNAUTHENTICATED на всём")
    void anonymousIsRefusedEverywhere(Endpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request().apply(fixture))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    /**
     * Обратная сторона трёх строк «(чужой)» в таблице: MEMBER не может удалить чужое, но
     * своё — может. Без этого теста матрица разрешала бы закрыть удаление для MEMBER совсем,
     * и она бы осталась зелёной.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ownResources")
    @DisplayName("MEMBER удаляет то, что создал сам")
    void memberMayDeleteTheirOwn(String name, Function<Fixture, String> path) throws Exception {
        mockMvc.perform(delete(path.apply(fixture)).header(AUTHORIZATION, tokenOf(ProjectRole.MEMBER)))
                .andExpect(status().isNoContent());
    }

    /**
     * Страховка от тихого исчезновения строк: таблица легко «худеет» при рефакторинге, и
     * поредевшая матрица выглядит так же зелено, как полная. Число обновляется руками —
     * вместе с добавлением строки, а не вместо него.
     */
    @Test
    @DisplayName("в таблице учтены все эндпоинты проекта")
    void matrixCoversEveryProjectEndpoint() {
        assertThat(ENDPOINTS).hasSize(43);
        assertThat(ENDPOINTS).extracting(Endpoint::name).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ источники данных

    private static Stream<Arguments> roleMatrix() {
        return ENDPOINTS.stream().flatMap(endpoint -> endpoint.expected().entrySet().stream()
                .map(cell -> arguments(endpoint, cell.getKey(), cell.getValue())));
    }

    private static Stream<Endpoint> allEndpoints() {
        return ENDPOINTS.stream();
    }

    private static Stream<Arguments> ownResources() {
        return Stream.of(
                arguments("свой комментарий", (Function<Fixture, String>) f -> "/api/comments/" + f.ownCommentId),
                arguments("своя запись времени", (Function<Fixture, String>) f -> "/api/time-logs/" + f.ownTimeLogId),
                arguments("своё вложение", (Function<Fixture, String>) f -> "/api/attachments/" + f.ownAttachmentId));
    }

    private static Endpoint endpoint(String name,
                                     Function<Fixture, AbstractMockHttpServletRequestBuilder<?>> request,
                                     Access owner, Access admin, Access member, Access viewer) {
        Map<ProjectRole, Access> expected = new EnumMap<>(ProjectRole.class);
        expected.put(ProjectRole.OWNER, owner);
        expected.put(ProjectRole.ADMIN, admin);
        expected.put(ProjectRole.MEMBER, member);
        expected.put(ProjectRole.VIEWER, viewer);
        return new Endpoint(name, request, expected);
    }

    private ResultActions call(Endpoint endpoint, String bearerToken) throws Exception {
        return mockMvc.perform(endpoint.request().apply(fixture).header(AUTHORIZATION, bearerToken));
    }

    // ------------------------------------------------------------------------- фикстура

    /** Всё, на что ссылаются запросы из таблицы. Пересоздаётся перед каждым запуском. */
    private record Fixture(UUID projectId, String projectSlug,
                           UUID taskId, int taskNumber,
                           UUID viewerUserId, String outsiderEmail,
                           UUID categoryId, UUID tagId,
                           UUID commentId, UUID timeLogId, UUID attachmentId,
                           UUID ownCommentId, UUID ownTimeLogId, UUID ownAttachmentId) {
    }

    /**
     * Пароль в фикстуре не используется ни разу: токены выписываются напрямую через JwtService,
     * а не через /api/auth/login. Это не срезание угла — вход уже проверен в AuthFlowIntegrationTest,
     * а BCrypt на каждого из шести пользователей в каждом из ~250 запусков стоил бы минуты.
     */
    private static final String UNUSED_PASSWORD_HASH = "$2a$10$fixture.hash.never.verified.by.these.tests......";

    private static final String PREVIEW_IMAGE_PATH = "fixtures/preview.png";
    private static final String ATTACHMENT_PATH = "fixtures/note.txt";

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskCommentRepository taskCommentRepository;
    @Autowired private TimeLogRepository timeLogRepository;
    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TagRepository tagRepository;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    private Fixture fixture;
    private Map<ProjectRole, String> bearerTokens;
    private String outsiderToken;

    /**
     * Фикстура собирается через репозитории, а не через API: собрать её HTTP-запросами значило бы
     * гонять по десятку запросов перед каждой из ~250 проверок, а главное — пользоваться при этом
     * теми самыми эндпоинтами, права которых мы и проверяем.
     * <p>
     * Пересоздаётся целиком перед каждым запуском, и иначе нельзя: половина строк таблицы что-то
     * удаляет (проект, задачу, тег), и разрешённый вызов уносит с собой фикстуру следующего.
     */
    @BeforeEach
    void createFixture() {
        User owner = user("owner@example.com");
        User admin = user("admin@example.com");
        User member = user("member@example.com");
        User viewer = user("viewer@example.com");
        User outsider = user("outsider@example.com");
        // Отдельный участник, от чьего имени созданы «чужие» комментарий/запись времени/вложение.
        // Именно отдельный: будь автором кто-то из четырёх ролей, для него строка «(чужой)»
        // проверяла бы не то, что написано в таблице.
        User someoneElse = user("someone.else@example.com");

        Project project = new Project();
        project.setName("Permission matrix project");
        project.setSlug("permission-matrix-project");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        membership(project, owner, ProjectRole.OWNER);
        membership(project, admin, ProjectRole.ADMIN);
        membership(project, member, ProjectRole.MEMBER);
        membership(project, viewer, ProjectRole.VIEWER);
        membership(project, someoneElse, ProjectRole.MEMBER);

        // Файлы кладём на диск по-настоящему: без них GET preview-image и download отдали бы 404,
        // и строка таблицы проверяла бы не права, а наличие файла.
        writeStorageFile(PREVIEW_IMAGE_PATH, PNG_BYTES);
        writeStorageFile(ATTACHMENT_PATH, "plain text".getBytes(StandardCharsets.UTF_8));
        project.setPreviewImagePath(PREVIEW_IMAGE_PATH);
        projectRepository.save(project);

        // Номер резервируем тем же счётчиком, что и TaskService: иначе задача, созданная через
        // API в этом же тесте, получила бы тот же номер и упёрлась в uq_tasks_project_task_number.
        int taskNumber = projectRepository.reserveNextTaskNumber(project.getId());
        Task task = new Task();
        task.setProject(project);
        task.setTaskNumber(taskNumber);
        task.setTitle("Fixture task");
        task.setStatus(TaskStatus.NEW);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setPosition(0);
        task.setCreatedBy(owner);
        taskRepository.save(task);

        Category category = new Category();
        category.setProject(project);
        category.setName("Fixture category");
        category.setCreatedBy(owner);
        categoryRepository.save(category);

        Tag tag = new Tag();
        tag.setProject(project);
        tag.setName("fixture-tag");
        tag.setColor("#AABBCC");
        tag.setCreatedBy(owner);
        tagRepository.save(tag);

        TaskComment foreignComment = comment(task, someoneElse);
        TimeLog foreignTimeLog = timeLog(task, someoneElse);
        Attachment foreignAttachment = attachment(task, someoneElse);

        TaskComment ownComment = comment(task, member);
        TimeLog ownTimeLog = timeLog(task, member);
        Attachment ownAttachment = attachment(task, member);

        fixture = new Fixture(project.getId(), project.getSlug(),
                task.getId(), taskNumber,
                viewer.getId(), outsider.getEmail(),
                category.getId(), tag.getId(),
                foreignComment.getId(), foreignTimeLog.getId(), foreignAttachment.getId(),
                ownComment.getId(), ownTimeLog.getId(), ownAttachment.getId());

        bearerTokens = new EnumMap<>(Map.of(
                ProjectRole.OWNER, bearer(owner),
                ProjectRole.ADMIN, bearer(admin),
                ProjectRole.MEMBER, bearer(member),
                ProjectRole.VIEWER, bearer(viewer)));
        outsiderToken = bearer(outsider);
    }

    private String tokenOf(ProjectRole role) {
        return bearerTokens.get(role);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(UNUSED_PASSWORD_HASH);
        user.setLastName("Тестов");
        user.setFirstName(email.substring(0, email.indexOf('@')));
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private ProjectMember membership(Project project, User user, ProjectRole role) {
        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(role);
        return projectMemberRepository.save(member);
    }

    private TaskComment comment(Task task, User author) {
        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setAuthor(author);
        comment.setBody("comment by " + author.getEmail());
        return taskCommentRepository.save(comment);
    }

    private TimeLog timeLog(Task task, User user) {
        TimeLog timeLog = new TimeLog();
        timeLog.setTask(task);
        timeLog.setUser(user);
        timeLog.setHours(new BigDecimal("1.00"));
        timeLog.setSpentOn(LocalDate.now());
        return timeLogRepository.save(timeLog);
    }

    private Attachment attachment(Task task, User uploader) {
        Attachment attachment = new Attachment();
        attachment.setTask(task);
        attachment.setUploadedBy(uploader);
        attachment.setOriginalFilename("note.txt");
        attachment.setStoredPath(ATTACHMENT_PATH);
        attachment.setContentType("text/plain");
        attachment.setSizeBytes(10);
        return attachmentRepository.save(attachment);
    }

    /**
     * Перезаписывается каждый раз: разрешённое удаление проекта или вложения сносит файл
     * с диска вместе со строкой в БД.
     */
    private void writeStorageFile(String relativePath, byte[] content) {
        try {
            Path file = Paths.get(storageBasePath).toAbsolutePath().normalize().resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to prepare the fixture file " + relativePath, e);
        }
    }

    private static byte[] pngBytes() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build the fixture PNG", e);
        }
    }
}
