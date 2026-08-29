package com.pmtracker.project_management_backend.common.scheduling;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.notification.NotificationRepository;
import com.pmtracker.project_management_backend.notification.NotificationScheduler;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Взаимное исключение заданий по расписанию между инстансами (3.8).
 * <p>
 * Второго инстанса приложения в тесте нет и быть не может, но он и не нужен: с точки зрения
 * Postgres «другой инстанс» — это просто другое соединение, держащее ту же
 * advisory-блокировку. Тест берёт её на отдельном соединении и проверяет, что скан дедлайнов
 * при этом не делает ничего, а после освобождения — делает.
 * <p>
 * Проверяется именно результат (созданы уведомления или нет), а не возвращаемое значение
 * блокировки: смысл всей затеи в том, что человек не получает два одинаковых письма.
 */
class SchedulerLockIntegrationTest extends IntegrationTest {

    /** Значение SchedulerLockKey.NOTIFICATION_DUE_SCAN; ключ пакетно-приватный, а SQL здесь свой. */
    private static final long DUE_SCAN_KEY = 7_710_001L;

    @Autowired private DataSource dataSource;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationScheduler notificationScheduler;

    private User assignee;
    private Project project;

    @BeforeEach
    void createOverdueTask() {
        assignee = new User();
        assignee.setEmail("assignee@example.com");
        assignee.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        assignee.setLastName("Тестов");
        assignee.setFirstName("Исполнитель");
        assignee.setEmailVerified(true);
        userRepository.save(assignee);

        project = new Project();
        project.setName("Lock project");
        project.setSlug("lock-project");
        project.setCreatedBy(assignee);
        projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(assignee);
        membership.setRole(ProjectRole.OWNER);
        projectMemberRepository.save(membership);

        Task task = new Task();
        task.setProject(project);
        task.setTaskNumber(projectRepository.reserveNextTaskNumber(project.getId()));
        task.setTitle("Просроченная задача");
        task.setStatus(TaskStatus.NEW);
        task.setUrgency(TaskUrgency.MEDIUM);
        task.setAssignee(assignee);
        task.setDueDate(Instant.now().minus(1, ChronoUnit.DAYS));
        task.setCreatedBy(assignee);
        taskRepository.save(task);
    }

    @Test
    @DisplayName("пока блокировку держит другое соединение, скан не создаёт уведомлений")
    void skipsTheScanWhileAnotherInstanceHoldsTheLock() throws SQLException {
        try (Connection other = dataSource.getConnection()) {
            acquireSessionLock(other);

            notificationScheduler.checkDueDates();

            assertThat(notificationRepository.count()).isZero();
        }
    }

    @Test
    @DisplayName("после освобождения блокировки следующий тик отрабатывает как обычно")
    void runsOnceTheLockIsFree() throws SQLException {
        try (Connection other = dataSource.getConnection()) {
            acquireSessionLock(other);
            notificationScheduler.checkDueDates();
            assertThat(notificationRepository.count()).isZero();
            releaseSessionLock(other);
        }

        notificationScheduler.checkDueDates();

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("без конкурентов блокировка не мешает: скан работает как раньше")
    void doesNotGetInItsOwnWay() {
        notificationScheduler.checkDueDates();

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    /**
     * Сессионная блокировка, а не транзакционная: держать её надо дольше одного statement'а,
     * пока соседний поток выполняет скан. В приложении используется транзакционная — она
     * снимается сама, см. SchedulerLock.
     */
    private void acquireSessionLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, DUE_SCAN_KEY);
            statement.execute();
        }
    }

    private void releaseSessionLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, DUE_SCAN_KEY);
            statement.execute();
        }
    }
}
