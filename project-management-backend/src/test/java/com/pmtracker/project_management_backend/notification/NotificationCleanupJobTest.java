package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Чистка прочитанных уведомлений (3.9).
 * <p>
 * Строки заводятся напрямую в БД: тесту нужны конкретные значения read_at, в том числе
 * трёхмесячной давности, а через API их не проставить — уведомление помечается прочитанным
 * текущим временем.
 */
class NotificationCleanupJobTest extends IntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationCleanupJob cleanupJob;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User recipient;

    @BeforeEach
    void createRecipient() {
        recipient = new User();
        recipient.setEmail("recipient@example.com");
        recipient.setUsername(usernameFrom("recipient@example.com"));
        recipient.setPasswordHash("$2a$10$fixture.hash.never.verified.by.these.tests......");
        recipient.setLastName("Тестов");
        recipient.setFirstName("Получатель");
        recipient.setEmailVerified(true);
        userRepository.save(recipient);
    }

    @Test
    @DisplayName("прочитанное давно удаляется, прочитанное недавно остаётся")
    void deletesOnlyLongReadNotifications() {
        insert("old", readAt(NotificationCleanupJob.READ_RETENTION.plusDays(1)));
        insert("recent", readAt(Duration.ofDays(1)));

        cleanupJob.deleteOldReadNotifications();

        assertThat(remainingTypes()).containsExactly("recent");
    }

    /**
     * Непрочитанное не удаляется никогда: человек его ещё не видел, а счётчик на
     * колокольчике уменьшился бы сам собой.
     */
    @Test
    @DisplayName("непрочитанное не трогается, каким бы старым ни было")
    void neverDeletesUnreadNotifications() {
        insert("ancient-unread", null);

        cleanupJob.deleteOldReadNotifications();

        assertThat(remainingTypes()).containsExactly("ancient-unread");
    }

    /**
     * Срок отсчитывается от прочтения, а не от создания: уведомление, созданное сто дней
     * назад и прочитанное вчера, только что показали пользователю.
     */
    @Test
    @DisplayName("старое, но недавно прочитанное — остаётся")
    void measuresAgeFromWhenItWasRead() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(100));
        insert("old-but-just-read", Instant.now().minus(Duration.ofDays(1)), longAgo);

        cleanupJob.deleteOldReadNotifications();

        assertThat(remainingTypes()).containsExactly("old-but-just-read");
    }

    @Test
    @DisplayName("повторный запуск удаляет ноль строк — джоб идемпотентен")
    void isIdempotent() {
        insert("old", readAt(NotificationCleanupJob.READ_RETENTION.plusDays(1)));

        cleanupJob.deleteOldReadNotifications();
        cleanupJob.deleteOldReadNotifications();

        assertThat(notificationRepository.count()).isZero();
    }

    // ------------------------------------------------------------------------ хелперы

    private static Instant readAt(Duration ago) {
        return Instant.now().minus(ago);
    }

    private void insert(String type, Instant readAt) {
        insert(type, readAt, Instant.now());
    }

    private void insert(String type, Instant readAt, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO notifications (recipient_id, type, payload, read_at, created_at)
                VALUES (?, ?, '{}'::jsonb, ?, ?)
                """, recipient.getId(), type,
                readAt == null ? null : Timestamp.from(readAt), Timestamp.from(createdAt));
    }

    private List<String> remainingTypes() {
        return jdbcTemplate.queryForList("SELECT type FROM notifications ORDER BY type", String.class);
    }
}
