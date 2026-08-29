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
import com.pmtracker.project_management_backend.wiki.ProjectWikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Оптимистичные блокировки на задаче, проекте и вики (3.4).
 * <p>
 * Проверяется то, что раньше происходило молча: двое открыли одну форму, первый сохранил,
 * второй сохранил поверх — и правка первого исчезла без следа. Теперь второй получает 409, а
 * данные остаются как их оставил первый.
 * <p>
 * Каждый тест смотрит на состояние ПОСЛЕ отказа, а не только на код ответа: смысл здесь
 * именно в том, что при конфликте не записалось ничего — ни поля, ни события в ленте.
 */
class OptimisticLockingIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private ProjectWikiRepository projectWikiRepository;

    private User owner;
    private Project project;
    private String authHeader;

    @BeforeEach
    void createProject() {
        owner = new User();
        owner.setEmail("owner@example.com");
        owner.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        owner.setLastName("Тестов");
        owner.setFirstName("Владелец");
        owner.setEmailVerified(true);
        userRepository.save(owner);

        project = new Project();
        project.setName("Locking project");
        project.setSlug("locking-project");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(owner);
        membership.setRole(ProjectRole.OWNER);
        projectMemberRepository.save(membership);

        authHeader = "Bearer " + jwtService.generateAccessToken(owner);
    }

    @Nested
    @DisplayName("задача")
    class Tasks {

        @Test
        @DisplayName("версия отдаётся в ответе и растёт с каждым сохранением")
        void exposesAndIncrementsTheVersion() throws Exception {
            Task task = task("Исходное название");

            mockMvc.perform(get("/api/tasks/" + task.getId()).header(AUTHORIZATION, authHeader))
                    .andExpect(jsonPath("$.version").value(0));

            editTask(task, "Первая правка", 0).andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1));
            editTask(task, "Вторая правка", 1).andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2));
        }

        /**
         * Тот самый сценарий из 3.4: две открытые формы. Обе загрузили версию 0, первая
         * сохранилась, вторая приходит с той же нулевой версией.
         */
        @Test
        @DisplayName("вторая форма со старой версией — 409, правка первой не затёрта")
        void rejectsAStaleVersion() throws Exception {
            Task task = task("Исходное название");

            editTask(task, "Правка первого", 0).andExpect(status().isOk());

            editTask(task, "Правка второго", 0)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CONCURRENT_MODIFICATION"));

            assertThat(reload(task).getTitle()).isEqualTo("Правка первого");
        }

        @Test
        @DisplayName("версия из будущего тоже конфликт, а не молчаливое сохранение")
        void rejectsAVersionAhead() throws Exception {
            Task task = task("Исходное название");

            editTask(task, "Из будущего", 99)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CONCURRENT_MODIFICATION"));

            assertThat(reload(task).getTitle()).isEqualTo("Исходное название");
        }

        /**
         * Версия обязательна: если бы её отсутствие означало «проверять не надо», защиту
         * от затирания чужих правок отключал бы забытый параметр.
         */
        @Test
        @DisplayName("запрос без версии — 400, а не сохранение без проверки")
        void requiresTheVersion() throws Exception {
            Task task = task("Исходное название");

            mockMvc.perform(patch("/api/tasks/" + task.getId())
                            .header(AUTHORIZATION, authHeader)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"title":"Без версии","status":"NEW","urgency":"MEDIUM"}"""))
                    .andExpect(status().isBadRequest());

            assertThat(reload(task).getTitle()).isEqualTo("Исходное название");
        }

        /**
         * Проверка стоит до применения правок — иначе отклонённое сохранение всё равно
         * оставило бы после себя записи «поле изменено» в ленте активности проекта.
         */
        @Test
        @DisplayName("конфликт не оставляет событий в ленте активности")
        void aRejectedEditRecordsNothing() throws Exception {
            Task task = task("Исходное название");
            editTask(task, "Правка первого", 0).andExpect(status().isOk());

            long activityBefore = countActivity();
            editTask(task, "Правка второго", 0).andExpect(status().isConflict());

            assertThat(countActivity()).isEqualTo(activityBefore);
        }
    }

    @Nested
    @DisplayName("проект")
    class Projects {

        @Test
        @DisplayName("настройки со старой версией — 409, изменения первого сохранения на месте")
        void rejectsAStaleVersion() throws Exception {
            editProject("Первое имя", 0).andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1));

            editProject("Второе имя", 0)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CONCURRENT_MODIFICATION"));

            assertThat(projectRepository.findById(project.getId()).orElseThrow().getName())
                    .isEqualTo("Первое имя");
        }
    }

    @Nested
    @DisplayName("вики")
    class Wiki {

        /**
         * У проекта без вики версия -1, а не 0: строки в БД ещё нет, а у только что
         * созданной версия как раз 0, и совпадение этих двух значений разрешило бы второму
         * автору затереть первую же сохранённую страницу.
         */
        @Test
        @DisplayName("до первого сохранения версия -1, и создание проходит с ней")
        void marksAMissingWikiWithItsOwnVersion() throws Exception {
            mockMvc.perform(get("/api/projects/" + project.getId() + "/wiki").header(AUTHORIZATION, authHeader))
                    .andExpect(jsonPath("$.content").value(""))
                    .andExpect(jsonPath("$.version").value(-1));

            editWiki("# Первая страница", -1).andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(0));
        }

        @Test
        @DisplayName("второй автор, открывший пустой редактор, не затирает первую страницу")
        void rejectsASecondCreation() throws Exception {
            editWiki("# Страница первого", -1).andExpect(status().isOk());

            editWiki("# Страница второго", -1)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CONCURRENT_MODIFICATION"));

            assertThat(projectWikiRepository.findByProjectId(project.getId()).orElseThrow().getContent())
                    .isEqualTo("# Страница первого");
        }

        /**
         * Для вики это самый дорогой из трёх случаев: страница одна на проект и целиком
         * состоит из одного текста, поэтому «победил сохранивший последним» здесь означает
         * потерю всей чужой работы, а не одного поля.
         */
        @Test
        @DisplayName("вторая вкладка со старой версией — 409, текст первой не затёрт")
        void rejectsAStaleVersion() throws Exception {
            editWiki("# Начальный текст", -1).andExpect(status().isOk());
            editWiki("# Текст первого", 0).andExpect(status().isOk());

            editWiki("# Текст второго", 0)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("CONCURRENT_MODIFICATION"));

            assertThat(projectWikiRepository.findByProjectId(project.getId()).orElseThrow().getContent())
                    .isEqualTo("# Текст первого");
        }
    }

    // ------------------------------------------------------------------------ хелперы

    private ResultActions editTask(Task task, String title, long version) throws Exception {
        return mockMvc.perform(patch("/api/tasks/" + task.getId())
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"title":"%s","status":"NEW","urgency":"MEDIUM","version":%d}"""
                        .formatted(title, version)));
    }

    private ResultActions editProject(String name, long version) throws Exception {
        return mockMvc.perform(patch("/api/projects/" + project.getId())
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"name":"%s","description":null,"archived":false,"version":%d}"""
                        .formatted(name, version)));
    }

    private ResultActions editWiki(String content, long version) throws Exception {
        return mockMvc.perform(put("/api/projects/" + project.getId() + "/wiki")
                .header(AUTHORIZATION, authHeader)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"content":"%s","version":%d}""".formatted(content, version)));
    }

    private Task task(String title) {
        Task task = new Task();
        task.setProject(project);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        task.setTitle(title);
        task.setStatus(TaskStatus.NEW);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setCreatedBy(owner);
        return taskRepository.save(task);
    }

    private Task reload(Task task) {
        return taskRepository.findById(task.getId()).orElseThrow();
    }

    private long countActivity() throws Exception {
        return mockMvc.perform(get("/api/projects/" + project.getId() + "/activity")
                        .header(AUTHORIZATION, authHeader))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .split("\"id\"", -1).length - 1;
    }
}
