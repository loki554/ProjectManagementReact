package com.pmtracker.project_management_backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Суточная уборка отработавших токенов.
 * <p>
 * До неё не чистилось вообще ничего: подтверждения регистрации, ссылки сброса и refresh-токены
 * копились навсегда. Для первых двух это просто растущий мусор, а вот refresh_tokens участвует
 * в каждом обновлении сессии (поиск по token_hash), и таблица, растущая линейно по числу
 * обновлений всех пользователей за всё время, со временем делает эту операцию дороже без всякой
 * на то причины.
 * <p>
 * Джоб намеренно тупой и идемпотентный: никакого состояния, только «удалить всё, что старше
 * порога». Поэтому лишний запуск (несколько инстансов приложения, перезапуск) ничего не портит —
 * второй просто удалит ноль строк. Отдельной распределённой блокировки ради этого не заводим.
 */
@Component
public class TokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupJob.class);

    /**
     * Сколько refresh-токены живут в базе ПОСЛЕ истечения. Нужны не сами по себе, а ради
     * детекта повторного использования (см. RefreshTokenRepository.deleteExpiredBefore):
     * удалённый токен неотличим от никогда не существовавшего, и кража перестаёт опознаваться.
     * Месяц — с запасом больше TTL самого токена (14 дней), то есть украденная цепочка успевает
     * умереть своей смертью задолго до того, как исчезнут следы.
     */
    private static final Duration REFRESH_TOKEN_RETENTION = Duration.ofDays(30);

    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public TokenCleanupJob(EmailVerificationTokenRepository verificationTokenRepository,
                           PasswordResetTokenRepository passwordResetTokenRepository,
                           RefreshTokenRepository refreshTokenRepository) {
        this.verificationTokenRepository = verificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    // Расписание в свойстве, а не константой: по умолчанию 3:30 ночи (время, когда лишние
    // DELETE никому не мешают), но эксплуатации может понадобиться другое окно — а заодно
    // это единственный способ проверить джоб, не дожидаясь ночи.
    @Scheduled(cron = "${app.token-cleanup.cron:0 30 3 * * *}")
    @Transactional
    public void deleteStaleTokens() {
        Instant now = Instant.now();

        int verification = verificationTokenRepository.deleteExpired(now);
        int passwordReset = passwordResetTokenRepository.deleteExpired(now);
        int refresh = refreshTokenRepository.deleteExpiredBefore(now.minus(REFRESH_TOKEN_RETENTION));

        if (verification + passwordReset + refresh > 0) {
            log.info("Token cleanup: deleted {} verification, {} password reset and {} refresh token(s)",
                    verification, passwordReset, refresh);
        }
    }
}
