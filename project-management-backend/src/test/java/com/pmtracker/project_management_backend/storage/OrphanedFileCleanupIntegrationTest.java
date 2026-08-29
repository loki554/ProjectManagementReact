package com.pmtracker.project_management_backend.storage;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сверка хранилища с базой (3.6).
 * <p>
 * Главная проверка здесь — сквозная: удалить проект через API и убедиться, что файл его
 * вложения не остался на диске навсегда. Именно в таком виде утечка и существовала: строка
 * attachments уходила по ON DELETE CASCADE, а файл — нет, и заметить это по поведению
 * приложения было невозможно.
 * <p>
 * Порог возраста в этом профиле обнулён: файл, записанный секунду назад, иначе считался бы
 * слишком свежим — что в проде и требуется, а в тесте означало бы ожидание суток.
 */
@TestPropertySource(properties = "app.storage.orphan-cleanup.minimum-age=PT0S")
class OrphanedFileCleanupIntegrationTest extends IntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrphanedFileCleanupJob cleanupJob;

    @Value("${app.storage.base-path}")
    private String basePath;

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
        project.setName("Storage project");
        project.setSlug("storage-project");
        project.setCreatedBy(owner);
        projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(owner);
        membership.setRole(ProjectRole.OWNER);
        projectMemberRepository.save(membership);

        authHeader = "Bearer " + jwtService.generateAccessToken(owner);
    }

    @Test
    @DisplayName("файл, на который никто не ссылается, удаляется с диска")
    void deletesAFileNoRowPointsAt() throws Exception {
        StoredFile orphan = store("orphan.txt");

        cleanupJob.deleteOrphanedFiles();

        assertThat(existsOnDisk(orphan)).isFalse();
    }

    @Test
    @DisplayName("файл живого вложения не трогается")
    void keepsAFileAnAttachmentPointsAt() throws Exception {
        Task task = task();
        StoredFile attached = store("attached.txt");
        insertAttachment(task, attached);

        cleanupJob.deleteOrphanedFiles();

        assertThat(existsOnDisk(attached)).isTrue();
    }

    /**
     * Вложения задачи, лежащей в корзине (3.5), обязаны пережить уборку: строки на месте,
     * задача восстановима, и вернуться она должна со своими файлами. Ради этого сверка
     * читает attachments нативным запросом, мимо @SQLRestriction на Task.
     */
    @Test
    @DisplayName("файлы задачи из корзины остаются на месте")
    void keepsFilesOfTrashedTasks() throws Exception {
        Task task = task();
        StoredFile attached = store("trashed.txt");
        insertAttachment(task, attached);

        mockMvc.perform(delete("/api/tasks/" + task.getId()).header(AUTHORIZATION, authHeader))
                .andExpect(status().isNoContent());

        cleanupJob.deleteOrphanedFiles();

        assertThat(existsOnDisk(attached)).isTrue();
    }

    @Test
    @DisplayName("аватарки и превью проектов тоже считаются используемыми")
    void keepsAvatarsAndPreviewImages() throws Exception {
        StoredFile avatar = store("avatar.png");
        StoredFile preview = store("preview.png");
        owner.setAvatarPath(avatar.relativePath());
        userRepository.save(owner);
        project.setPreviewImagePath(preview.relativePath());
        projectRepository.save(project);

        cleanupJob.deleteOrphanedFiles();

        assertThat(existsOnDisk(avatar)).isTrue();
        assertThat(existsOnDisk(preview)).isTrue();
    }

    /**
     * Тот самый сценарий, из-за которого сверка и появилась: удаление проекта уносит строки
     * вложений каскадом в БД, а файлы каскад не видит.
     */
    @Test
    @DisplayName("после удаления проекта файлы его вложений уходят с диска")
    void cleansUpAfterAProjectIsDeleted() throws Exception {
        Task task = task();
        StoredFile attached = store("doomed.txt");
        insertAttachment(task, attached);

        mockMvc.perform(delete("/api/projects/" + project.getId()).header(AUTHORIZATION, authHeader))
                .andExpect(status().isNoContent());

        assertThat(existsOnDisk(attached)).as("каскад в БД до файла не достаёт").isTrue();

        cleanupJob.deleteOrphanedFiles();

        assertThat(existsOnDisk(attached)).isFalse();
    }

    /**
     * Порог возраста существует ровно затем, чтобы уборка не удалила файл, который прямо
     * сейчас загружается: он уже на диске, а строки, ссылающейся на него, ещё нет.
     */
    @Test
    @DisplayName("свежий файл не трогается, даже если на него ещё никто не ссылается")
    void keepsFilesYoungerThanTheThreshold() throws Exception {
        StoredFile fresh = store("fresh.txt");
        // Сдвигаем не файл, а порог: в этом профиле он нулевой, и «свежесть» надо задать явно.
        Files.setLastModifiedTime(pathOf(fresh),
                java.nio.file.attribute.FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS)));

        cleanupJob.deleteOrphanedFiles();

        assertThat(existsOnDisk(fresh)).isTrue();
    }

    @Test
    @DisplayName("повторный запуск ничего не ломает — сверка идемпотентна")
    void isIdempotent() throws Exception {
        StoredFile orphan = store("orphan.txt");
        Task task = task();
        StoredFile attached = store("attached.txt");
        insertAttachment(task, attached);

        cleanupJob.deleteOrphanedFiles();
        cleanupJob.deleteOrphanedFiles();

        assertThat(existsOnDisk(orphan)).isFalse();
        assertThat(existsOnDisk(attached)).isTrue();
    }

    @Test
    @DisplayName("listAll видит записанные файлы и не видит удалённые")
    void listsWhatIsActuallyOnDisk() throws Exception {
        StoredFile kept = store("kept.txt");
        StoredFile removed = store("removed.txt");
        fileStorageService.delete(removed.relativePath());

        List<String> paths = fileStorageService.listAll().stream().map(StoredObject::relativePath).toList();

        assertThat(paths).contains(kept.relativePath()).doesNotContain(removed.relativePath());
    }

    // ------------------------------------------------------------------------ хелперы

    private StoredFile store(String name) throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", name, "text/plain",
                ("content of " + name).getBytes(StandardCharsets.UTF_8));
        return fileStorageService.store(file, "tests/" + project.getId());
    }

    private Path pathOf(StoredFile file) {
        return Paths.get(basePath).toAbsolutePath().normalize().resolve(file.relativePath());
    }

    private boolean existsOnDisk(StoredFile file) {
        return Files.exists(pathOf(file));
    }

    /**
     * Строка вложения заводится напрямую в БД: загрузка через API прогнала бы файл через
     * валидатор типов, а тесту нужен только факт ссылки на путь.
     */
    private void insertAttachment(Task task, StoredFile file) {
        jdbcTemplate.update("""
                INSERT INTO attachments (task_id, uploaded_by, stored_path, original_filename, content_type, size_bytes)
                VALUES (?, ?, ?, ?, ?, ?)
                """, task.getId(), owner.getId(), file.relativePath(), "note.txt", "text/plain", file.sizeBytes());
    }

    private Task task() {
        Task task = new Task();
        task.setProject(project);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        task.setTitle("Задача с вложением");
        task.setStatus(TaskStatus.NEW);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setCreatedBy(owner);
        return taskRepository.save(task);
    }

}
