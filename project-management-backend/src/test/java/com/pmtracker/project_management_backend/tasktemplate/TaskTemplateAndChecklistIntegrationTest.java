package com.pmtracker.project_management_backend.tasktemplate;

import com.jayway.jsonpath.JsonPath;
import com.pmtracker.project_management_backend.auth.JwtService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.checklist.ChecklistItemRepository;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.tag.TagRepository;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

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
 * Шаблоны задач и чек-листы (4.13): {@code /api/projects/{id}/task-templates},
 * {@code /api/task-templates/{id}}, {@code /api/tasks/{id}/checklist},
 * {@code /api/checklist-items/{id}} и заведение задачи по шаблону.
 *
 * <p>Проверяется то, чего не видно из кода одного класса. Во-первых, стык шаблона и задачи:
 * чек-лист обязан приехать копией, а не ссылкой — правка шаблона не должна догонять уже
 * созданные задачи, а удаление шаблона не должно уносить их чек-листы. Во-вторых, права:
 * шаблонами распоряжается ADMIN, а чек-листом — любой MEMBER, при том что читают и то и
 * другое все, включая VIEWER; три разные границы в одном пункте легко перепутать местами,
 * не сломав ничего заметного. В-третьих, счётчик «сделано из всего», который приезжает в
 * каждом ответе о задаче и считается батчем на весь список.
 */
class TaskTemplateAndChecklistIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private ChecklistItemRepository checklistItemRepository;

    private User owner;
    private User member;
    private User viewer;
    private User outsider;
    private Project project;
    private String ownerHeader;
    private String memberHeader;
    private String viewerHeader;
    private String outsiderHeader;

    @BeforeEach
    void createProject() {
        owner = saveUser("owner@example.com", "Яковлев", "Олег");
        member = saveUser("member@example.com", "Петров", "Пётр");
        viewer = saveUser("viewer@example.com", "Абрамов", "Игорь");
        outsider = saveUser("outsider@example.com", "Чужой", "Человек");

        project = saveProject("Template project", "template-project");
        join(project, owner, ProjectRole.OWNER);
        join(project, member, ProjectRole.MEMBER);
        join(project, viewer, ProjectRole.VIEWER);

        ownerHeader = "Bearer " + jwtService.generateAccessToken(owner);
        memberHeader = "Bearer " + jwtService.generateAccessToken(member);
        viewerHeader = "Bearer " + jwtService.generateAccessToken(viewer);
        outsiderHeader = "Bearer " + jwtService.generateAccessToken(outsider);
    }

    // ------------------------------------------------------------------------- шаблоны

    @Nested
    @DisplayName("шаблоны задач")
    class Templates {

        @Test
        @DisplayName("шаблон заводится вместе с чек-листом и хранит порядок пунктов")
        void createsTemplateWithChecklist() throws Exception {
            createTemplate(ownerHeader, """
                    {"name":"Релиз","title":"Релиз 0.0","urgency":"HIGH",
                     "items":[{"content":"Собрать"},{"content":"Выкатить"},{"content":"Написать в чат"}]}
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Релиз"))
                    .andExpect(jsonPath("$.title").value("Релиз 0.0"))
                    .andExpect(jsonPath("$.urgency").value("HIGH"))
                    .andExpect(jsonPath("$.itemCount").value(3))
                    .andExpect(jsonPath("$.items", hasSize(3)))
                    .andExpect(jsonPath("$.items[0].content").value("Собрать"))
                    .andExpect(jsonPath("$.items[0].position").value(0))
                    .andExpect(jsonPath("$.items[2].content").value("Написать в чат"))
                    .andExpect(jsonPath("$.items[2].position").value(2));
        }

        /**
         * Шаблон, состоящий из одного чек-листа, — законный и, вероятно, самый частый: он
         * ради списка шагов и заводится. Тест фиксирует, что пустые поля не считаются
         * ошибкой и приезжают обратно как null, а не как пустые строки.
         */
        @Test
        @DisplayName("шаблон без единого поля задачи — это просто чек-лист")
        void allowsChecklistOnlyTemplate() throws Exception {
            createTemplate(ownerHeader, """
                    {"name":"Онбординг","title":"","description":"",
                     "items":[{"content":"Выдать доступы"}]}
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value(nullValue()))
                    .andExpect(jsonPath("$.description").value(nullValue()))
                    .andExpect(jsonPath("$.urgency").value(nullValue()))
                    .andExpect(jsonPath("$.tag").value(nullValue()))
                    .andExpect(jsonPath("$.itemCount").value(1));
        }

        @Test
        @DisplayName("имя шаблона уникально в проекте")
        void rejectsDuplicateName() throws Exception {
            createTemplate(ownerHeader, """
                    {"name":"Релиз","items":[]}""").andExpect(status().isCreated());
            createTemplate(ownerHeader, """
                    {"name":"Релиз","items":[]}""")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("DUPLICATE_TASK_TEMPLATE_NAME"));
        }

        /**
         * Правка переписывает чек-лист целиком тем списком, что пришёл, — в том числе
         * пустым. «Убрал все шаги» должно означать именно это, а не «не прислал список».
         */
        @Test
        @DisplayName("правка заменяет чек-лист целиком, включая опустошение")
        void updateReplacesChecklist() throws Exception {
            UUID templateId = createdTemplateId("""
                    {"name":"Релиз","items":[{"content":"Собрать"},{"content":"Выкатить"}]}""");

            mockMvc.perform(put("/api/task-templates/" + templateId)
                            .header(AUTHORIZATION, ownerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Релиз","items":[{"content":"Проверить логи"}]}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].content").value("Проверить логи"));

            mockMvc.perform(put("/api/task-templates/" + templateId)
                            .header(AUTHORIZATION, ownerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Релиз","items":[]}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.itemCount").value(0));
        }

        @Test
        @DisplayName("тэг из другого проекта в шаблон не берётся")
        void rejectsForeignTag() throws Exception {
            Project other = saveProject("Other", "other-project");
            join(other, owner, ProjectRole.OWNER);
            Tag foreignTag = new Tag();
            foreignTag.setProject(other);
            foreignTag.setName("чужой");
            foreignTag.setColor("#112233");
            foreignTag.setCreatedBy(owner);
            tagRepository.save(foreignTag);

            createTemplate(ownerHeader, """
                    {"name":"Релиз","tagId":"%s","items":[]}""".formatted(foreignTag.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("TAG_PROJECT_MISMATCH"));
        }

        /**
         * Границы прав в этом пункте: ADMIN и выше распоряжаются шаблонами, MEMBER — нет,
         * а читают их все, включая VIEWER, потому что список шаблонов нужен форме заведения
         * задачи (которую VIEWER, впрочем, всё равно не отправит).
         */
        @Test
        @DisplayName("права: заводит ADMIN, MEMBER не может, читает даже VIEWER")
        void permissions() throws Exception {
            createTemplate(memberHeader, """
                    {"name":"Релиз","items":[]}""")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));

            createTemplate(ownerHeader, """
                    {"name":"Релиз","items":[]}""").andExpect(status().isCreated());

            mockMvc.perform(get("/api/projects/" + project.getId() + "/task-templates")
                            .header(AUTHORIZATION, viewerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    // В списке пунктов нет — только их число.
                    .andExpect(jsonPath("$[0].items", hasSize(0)));

            mockMvc.perform(get("/api/projects/" + project.getId() + "/task-templates")
                            .header(AUTHORIZATION, outsiderHeader))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------- шаблон → задача

    @Nested
    @DisplayName("задача по шаблону")
    class FromTemplate {

        /**
         * Главная проверка стыка: чек-лист шаблона приезжает в задачу копией, в том же
         * порядке и неотмеченным, а счётчик в ответе о задаче сразу это показывает.
         */
        @Test
        @DisplayName("чек-лист шаблона копируется в задачу неотмеченным и по порядку")
        void copiesChecklistIntoTask() throws Exception {
            UUID templateId = createdTemplateId("""
                    {"name":"Релиз","items":[{"content":"Собрать"},{"content":"Выкатить"}]}""");

            String response = mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Релиз 2.7","templateId":"%s"}""".formatted(templateId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.checklistTotal").value(2))
                    .andExpect(jsonPath("$.checklistDone").value(0))
                    .andReturn().getResponse().getContentAsString();

            UUID taskId = UUID.fromString(JsonPath.read(response, "$.id"));
            mockMvc.perform(get("/api/tasks/" + taskId + "/checklist").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].content").value("Собрать"))
                    .andExpect(jsonPath("$[0].done").value(false))
                    .andExpect(jsonPath("$[1].content").value("Выкатить"));
        }

        /**
         * Шаблон — заготовка, а не наследование. Правка шаблона не догоняет уже созданные
         * задачи, а удаление шаблона не уносит их чек-листы: связь между ними существует
         * ровно один момент — момент создания.
         */
        @Test
        @DisplayName("правка и удаление шаблона не трогают уже созданные задачи")
        void templateChangesDoNotFollowTasks() throws Exception {
            UUID templateId = createdTemplateId("""
                    {"name":"Релиз","items":[{"content":"Собрать"}]}""");
            UUID taskId = createTaskFromTemplate(templateId, "Релиз 2.7");

            mockMvc.perform(put("/api/task-templates/" + templateId)
                            .header(AUTHORIZATION, ownerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name":"Релиз","items":[{"content":"Совсем другой шаг"}]}"""))
                    .andExpect(status().isOk());
            mockMvc.perform(delete("/api/task-templates/" + templateId).header(AUTHORIZATION, ownerHeader))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/tasks/" + taskId + "/checklist").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].content").value("Собрать"));
        }

        @Test
        @DisplayName("шаблон из другого проекта в задачу не подставляется")
        void rejectsForeignTemplate() throws Exception {
            Project other = saveProject("Other", "other-project");
            join(other, owner, ProjectRole.OWNER);
            String body = """
                    {"name":"Чужой","items":[]}""";
            String created = mockMvc.perform(post("/api/projects/" + other.getId() + "/task-templates")
                            .header(AUTHORIZATION, ownerHeader)
                            .contentType(APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            UUID foreignTemplateId = UUID.fromString(JsonPath.read(created, "$.id"));

            mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Релиз","templateId":"%s"}""".formatted(foreignTemplateId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("TASK_TEMPLATE_PROJECT_MISMATCH"));
        }
    }

    // ------------------------------------------------------------------------ чек-листы

    @Nested
    @DisplayName("чек-лист задачи")
    class Checklists {

        @Test
        @DisplayName("пункты добавляются в конец, галочка ставится отдельным полем")
        void addsAndChecksItems() throws Exception {
            Task task = saveTask("Задача с шагами");

            UUID first = addItem(task, "Первый");
            addItem(task, "Второй");

            mockMvc.perform(patch("/api/checklist-items/" + first)
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"done":true}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.done").value(true))
                    // Текст не присылали — он обязан остаться прежним.
                    .andExpect(jsonPath("$.content").value("Первый"));

            mockMvc.perform(get("/api/tasks/" + task.getId() + "/checklist").header(AUTHORIZATION, memberHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].position").value(0))
                    .andExpect(jsonPath("$[1].position").value(1));
        }

        /**
         * Частичный PATCH — единственный в трекере, и ровно ради этого случая: клик по
         * галочке не должен присылать текст обратно. Обратная сторона — переименование
         * не должно снимать галочку.
         */
        @Test
        @DisplayName("переименование пункта не снимает галочку")
        void renameKeepsDone() throws Exception {
            Task task = saveTask("Задача с шагами");
            UUID itemId = addItem(task, "Первый");

            mockMvc.perform(patch("/api/checklist-items/" + itemId)
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"done":true}""")).andExpect(status().isOk());
            mockMvc.perform(patch("/api/checklist-items/" + itemId)
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"content":"Первый, исправленный"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").value("Первый, исправленный"))
                    .andExpect(jsonPath("$.done").value(true));
        }

        @Test
        @DisplayName("пустой текст пункта — ошибка запроса, а не способ его стереть")
        void rejectsBlankContent() throws Exception {
            Task task = saveTask("Задача с шагами");
            UUID itemId = addItem(task, "Первый");

            mockMvc.perform(patch("/api/checklist-items/" + itemId)
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"content":"   "}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }

        /**
         * Счётчик в ответе о задаче — то, из чего рисуется бейдж «3/7» на доске и в списке.
         * Считается он батчем на весь список, поэтому проверяется именно в списке.
         */
        @Test
        @DisplayName("счётчик «сделано из всего» приезжает в списке задач")
        void progressInTaskList() throws Exception {
            Task withChecklist = saveTask("С чек-листом");
            saveTask("Без чек-листа");
            UUID firstItem = addItem(withChecklist, "Первый");
            addItem(withChecklist, "Второй");
            mockMvc.perform(patch("/api/checklist-items/" + firstItem)
                            .header(AUTHORIZATION, memberHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"done":true}""")).andExpect(status().isOk());

            mockMvc.perform(get("/api/projects/" + project.getId() + "/tasks")
                            .header(AUTHORIZATION, viewerHeader)
                            .param("sort", "NUMBER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[0].checklistTotal").value(2))
                    .andExpect(jsonPath("$.items[0].checklistDone").value(1))
                    // Задача без чек-листа — «0 из 0», а не пропущенное поле.
                    .andExpect(jsonPath("$.items[1].checklistTotal").value(0))
                    .andExpect(jsonPath("$.items[1].checklistDone").value(0));
        }

        @Test
        @DisplayName("права: отмечает MEMBER, VIEWER только читает, посторонний не видит")
        void permissions() throws Exception {
            Task task = saveTask("Задача с шагами");
            UUID itemId = addItem(task, "Первый");

            mockMvc.perform(post("/api/tasks/" + task.getId() + "/checklist")
                            .header(AUTHORIZATION, viewerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"content":"Мой пункт"}"""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));

            mockMvc.perform(patch("/api/checklist-items/" + itemId)
                            .header(AUTHORIZATION, viewerHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"done":true}"""))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/tasks/" + task.getId() + "/checklist").header(AUTHORIZATION, viewerHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));

            mockMvc.perform(get("/api/tasks/" + task.getId() + "/checklist").header(AUTHORIZATION, outsiderHeader))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/tasks/" + task.getId() + "/checklist"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Чек-лист живёт и умирает вместе с задачей: ON DELETE CASCADE в схеме. Проверяется
         * на удалении задачи насовсем — то есть на чистке корзины, которая единственная
         * действительно стирает строку задачи.
         */
        @Test
        @DisplayName("пункты уходят вместе с удалённой задачей")
        void cascadesWithTask() throws Exception {
            Task task = saveTask("Задача с шагами");
            addItem(task, "Первый");
            assertThat(checklistItemRepository.findByTaskIdOrderByPositionAsc(task.getId())).hasSize(1);

            taskRepository.deleteById(task.getId());

            assertThat(checklistItemRepository.findByTaskIdOrderByPositionAsc(task.getId())).isEmpty();
        }
    }

    // -------------------------------------------------------------------------- фикстуры

    private ResultActions createTemplate(String authHeader, String body) throws Exception {
        return mockMvc.perform(post("/api/projects/" + project.getId() + "/task-templates")
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private UUID createdTemplateId(String body) throws Exception {
        String response = createTemplate(ownerHeader, body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private UUID createTaskFromTemplate(UUID templateId, String title) throws Exception {
        String response = mockMvc.perform(post("/api/projects/" + project.getId() + "/tasks")
                        .header(AUTHORIZATION, memberHeader)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"%s","templateId":"%s"}""".formatted(title, templateId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private UUID addItem(Task task, String content) throws Exception {
        String response = mockMvc.perform(post("/api/tasks/" + task.getId() + "/checklist")
                        .header(AUTHORIZATION, memberHeader)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"content":"%s"}""".formatted(content)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
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

    private Task saveTask(String title) {
        Task task = new Task();
        task.setProject(project);
        task.setTitle(title);
        task.setStatus(TaskStatus.NEW);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setPosition(0);
        task.setCreatedBy(owner);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        return taskRepository.save(task);
    }
}
