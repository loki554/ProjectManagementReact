package com.pmtracker.project_management_backend.mail;

import com.pmtracker.project_management_backend.config.AsyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Отправка исходящих писем: после коммита транзакции и в отдельном потоке.
 * <p>
 * Раньше {@code AuthService.register} звал {@code mailSender.send()} прямо внутри
 * {@code @Transactional}-метода, и это давало сразу две неприятности. Тормозящий SMTP держал
 * открытой транзакцию БД на всё время HTTP-запроса (в связке с пулом на 10 соединений в проде
 * это способ выесть пул целиком), а лежащий SMTP откатывал регистрацию целиком: пользователь
 * получал 500, хотя аккаунт был совершенно валиден и уже почти создан.
 * <p>
 * Теперь порядок обратный: транзакция коммитится, пользователь получает ответ, и только потом
 * фоновый поток идёт в SMTP. Письмо стало вещью, которая может не дойти, — поэтому здесь есть
 * ретраи, а у пользователя остаются {@code /auth/resend-verification} и повторный запрос сброса.
 */
@Component
public class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    /** Попыток всего, включая первую. */
    private static final int MAX_ATTEMPTS = 3;

    /** Пауза перед 2-й и 3-й попыткой. Секунды, а не минуты: письмо ждёт живой человек. */
    private static final long[] RETRY_DELAYS_MS = {5_000L, 15_000L};

    private final MailService mailService;

    public MailDispatcher(MailService mailService) {
        this.mailService = mailService;
    }

    /**
     * AFTER_COMMIT: письмо со ссылкой на токен не должно уйти раньше, чем этот токен реально
     * окажется в БД. Иначе быстрый пользователь успевает перейти по ссылке до коммита и получает
     * «токен недействителен», а при откате транзакции — рабочую ссылку на несуществующий аккаунт.
     * <p>
     * fallbackExecution: если события когда-нибудь начнут публиковать вне транзакции, письмо
     * отправится сразу, а не потеряется молча (по умолчанию слушатель в этом случае не вызывается).
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVerificationEmailRequested(VerificationEmailRequestedEvent event) {
        sendWithRetries("verification", () -> mailService.sendVerificationEmail(event.email(), event.token()));
    }

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordResetEmailRequested(PasswordResetEmailRequestedEvent event) {
        sendWithRetries("password reset", () -> mailService.sendPasswordResetEmail(event.email(), event.token()));
    }

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountAlreadyExistsEmailRequested(AccountAlreadyExistsEmailRequestedEvent event) {
        sendWithRetries("account already exists", () -> mailService.sendAccountAlreadyExistsEmail(event.email()));
    }

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProjectInvitationEmailRequested(ProjectInvitationEmailRequestedEvent event) {
        sendWithRetries("project invitation", () -> mailService.sendProjectInvitationEmail(
                event.email(), event.token(), event.projectName(), event.inviterName(), event.expiresInDays()));
    }

    /**
     * Уведомление, доставляемое почтой (4.3). Событие публикуется только тогда, когда
     * настройки получателя это разрешают, — решение «слать или нет» принимается в
     * транзакции, вместе с самим уведомлением, а не здесь (см. NotificationService).
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationEmailRequested(NotificationEmailRequestedEvent event) {
        sendWithRetries("notification", () -> mailService.sendNotificationEmail(
                event.email(), event.item(), event.unsubscribeToken()));
    }

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationDigestEmailRequested(NotificationDigestEmailRequestedEvent event) {
        sendWithRetries("notification digest", () -> mailService.sendNotificationDigestEmail(
                event.email(), event.items(), event.totalCount(), event.unsubscribeToken()));
    }

    /**
     * @param kind короткое название письма для логов — ни адреса, ни токена в лог не попадает:
     *             первое засоряло бы логи почтой пользователей, второе равносильно выдаче
     *             рабочей ссылки сброса всякому, кто дотянулся до логов.
     */
    private void sendWithRetries(String kind, Runnable send) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                send.run();
                if (attempt > 1) {
                    log.info("Sent {} email on attempt {}", kind, attempt);
                }
                return;
            } catch (MailException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Failed to send {} email after {} attempts, giving up "
                            + "(the user can request a new one)", kind, attempt, e);
                    return;
                }
                log.warn("Failed to send {} email (attempt {} of {}), retrying in {} ms",
                        kind, attempt, MAX_ATTEMPTS, RETRY_DELAYS_MS[attempt - 1], e);
                if (!sleep(RETRY_DELAYS_MS[attempt - 1])) {
                    return;
                }
            }
        }
    }

    /** @return false, если поток попросили остановиться — тогда бросаем ретраи и выходим. */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
