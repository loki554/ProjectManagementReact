package com.pmtracker.project_management_backend.support;

import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Locale;

import static org.awaitility.Awaitility.await;

/**
 * База для интеграционных тестов: настоящее приложение поверх настоящей Postgres и
 * настоящего SMTP-сервера.
 * <p>
 * Оба «настоящих» здесь принципиальны. Postgres — потому что схемой владеет Flyway, и на
 * H2 пришлось бы либо переписывать миграции, либо подменять их на ddl-auto, то есть
 * тестировать не ту схему, которая едет в прод. SMTP — потому что письмо со ссылкой
 * подтверждения это часть контракта регистрации: мок MailService доказал бы только то,
 * что метод позвали, а проверить хочется, что ушло письмо с работающей ссылкой.
 * <p>
 * Контейнер и почтовый сервер запускаются один раз на JVM, а не на класс: старт Postgres
 * это несколько секунд, и платить их за каждый тест-класс незачем. Останавливать их
 * вручную не нужно — контейнер уберёт Ryuk, GreenMail уедет вместе с JVM.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    /**
     * Та же версия, что в docker-compose.yml и в проде. Разъезд версий здесь означал бы,
     * что зелёные тесты ничего не говорят про ту БД, в которой приложение реально работает.
     */
    private static final String POSTGRES_IMAGE = "postgres:16";

    /** Сколько ждём письма: оно уходит асинхронно, уже после ответа на HTTP-запрос. */
    private static final Duration MAIL_TIMEOUT = Duration.ofSeconds(10);

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /**
     * Порт 0 — это «дай любой свободный» (GreenMail называет это dynamic port, реальный
     * номер потом отдаёт getSmtp().getPort()). Фиксированный порт вроде стандартного 3025
     * сделал бы тесты зависимыми от того, что ещё занято на машине, — а рядом вполне может
     * крутиться MailHog из docker-compose.
     */
    protected static final GreenMail SMTP =
            new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

    static {
        POSTGRES.start();
        SMTP.start();
    }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> SMTP.getSmtp().getBindTo());
        registry.add("spring.mail.port", () -> SMTP.getSmtp().getPort());
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Тесты не обёрнуты в транзакцию и не могут быть: письмо уходит по AFTER_COMMIT
     * (см. MailDispatcher), то есть при откатываемой транзакции не ушло бы вовсе, а ротация
     * refresh-токенов проверяется как раз через то, что реально осталось в базе. Значит,
     * состояние надо убирать явно — иначе тесты начинают зависеть от порядка выполнения.
     * <p>
     * TRUNCATE ... CASCADE, а не deleteAll(): все таблицы приложения так или иначе висят
     * на users через ON DELETE CASCADE, поэтому одна строка гарантированно чистит всё, что
     * мог оставить после себя предыдущий тест.
     */
    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        clearMailbox();
    }

    /**
     * Никнейм для тестовой фикстуры пользователя (V28). Выводится из локальной части адреса —
     * тем же способом, что бэкофилл в самой миграции, и по той же причине: адреса в фикстурах
     * говорящие («assignee@example.com»), и никнейм, выведенный из них, читается в тестах про
     * @упоминания как настоящий, а не как случайная строка.
     * <p>
     * Уникальности внутри класса это не гарантирует — её обеспечивают сами адреса, которые
     * в пределах одной фикстуры и так различаются локальной частью. Совпадение проявится
     * нарушением уникального индекса, то есть громко.
     */
    protected static String usernameFrom(String email) {
        String base = email.split("@")[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        base = base.substring(0, Math.min(base.length(), 30));
        return base.length() >= 3 ? base : base + "-user";
    }

    /** Выкидывает всё, что уже пришло на SMTP: дальше тест ждёт только своё письмо. */
    protected void clearMailbox() {
        try {
            SMTP.purgeEmailFromAllMailboxes();
        } catch (FolderException e) {
            throw new IllegalStateException("Failed to purge the GreenMail mailbox", e);
        }
    }

    /**
     * Ждёт ровно одно письмо и возвращает его. Ожидание, а не мгновенная проверка, потому
     * что отправка асинхронная: ответ на /register приходит раньше, чем письмо доезжает
     * до SMTP (это и есть смысл MailDispatcher).
     */
    protected MimeMessage awaitSingleEmail() {
        await().atMost(MAIL_TIMEOUT).until(() -> SMTP.getReceivedMessages().length >= 1);
        MimeMessage[] messages = SMTP.getReceivedMessages();
        if (messages.length != 1) {
            throw new AssertionError("Expected exactly one email, got " + messages.length);
        }
        return messages[0];
    }

    /**
     * Ждёт, пока придёт ровно {@code expected} писем, и отдаёт их. Нужно там, где одно
     * действие рассылает несколько писем сразу (например, приглашение в два проекта):
     * awaitSingleEmail в такой ситуации либо поймает первое пришедшее, либо упадёт —
     * в зависимости от того, кто из фоновых потоков успел раньше.
     */
    protected MimeMessage[] awaitEmails(int expected) {
        await().atMost(MAIL_TIMEOUT).until(() -> SMTP.getReceivedMessages().length >= expected);
        MimeMessage[] messages = SMTP.getReceivedMessages();
        if (messages.length != expected) {
            throw new AssertionError("Expected exactly " + expected + " emails, got " + messages.length);
        }
        return messages;
    }

    /**
     * Проверяет, что письма нет. Ждать нечего только на первый взгляд: отправка асинхронная,
     * поэтому «сейчас пусто» ещё не значит «не придёт». Даём почте фору и убеждаемся, что
     * ящик всё это время оставался пустым.
     */
    protected void assertNoEmailSent() {
        await().during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(2))
                .until(() -> SMTP.getReceivedMessages().length == 0);
    }
}
