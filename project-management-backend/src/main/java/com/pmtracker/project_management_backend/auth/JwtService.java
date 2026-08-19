package com.pmtracker.project_management_backend.auth;

import com.pmtracker.project_management_backend.config.JwtProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    /**
     * Ровно тот литерал, что стоит дефолтом у app.jwt.secret в application-dev.yml. Он лежит
     * в репозитории, то есть публично известен: кто угодно с доступом к исходникам подпишет
     * им токен от имени любого пользователя. Копия здесь нужна, чтобы приложение с этим
     * секретом просто не поднялось нигде, кроме dev, — при смене значения в yml менять и тут.
     */
    private static final String DEV_DEFAULT_SECRET =
            "dev-only-insecure-secret-please-override-in-prod-min-32-bytes!";

    /** HS256 требует ключ не короче 256 бит. */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties jwtProperties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties jwtProperties, Environment environment) {
        validateSecret(jwtProperties.getSecret(), environment);
        this.jwtProperties = jwtProperties;
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Проверка в конструкторе, а не где-то в рантайме, — сознательно: неверно настроенный
     * секрет должен ронять контекст на старте. Тихо работающее приложение с публично
     * известным ключом хуже упавшего: подделку токена никто не заметит, а падение видно сразу.
     *
     * Дефолт из dev-профиля разрешён только когда активен сам профиль dev. В любом другом
     * профиле секрет обязан прийти из окружения (JWT_SECRET) — если его не задали,
     * app.jwt.secret останется пустым и мы упадём на первой же проверке.
     */
    private static void validateSecret(String secret, Environment environment) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret is not configured. Set the JWT_SECRET environment variable "
                            + "to a random value of at least " + MIN_SECRET_BYTES + " bytes.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret is too short for HS256: at least " + MIN_SECRET_BYTES
                            + " bytes are required. Set JWT_SECRET to a longer random value.");
        }
        if (DEV_DEFAULT_SECRET.equals(secret) && !environment.matchesProfiles("dev")) {
            throw new IllegalStateException(
                    "app.jwt.secret is still the development default, which is public knowledge "
                            + "(it is committed to the repository). Set JWT_SECRET to a random "
                            + "secret of at least " + MIN_SECRET_BYTES + " bytes.");
        }
    }

    /**
     * Access-токен живёт недолго (по умолчанию 15 минут) и содержит id пользователя как subject.
     * Отзыв access-токенов не поддерживается (это ограничение JWT как такового) — компрометация
     * возможна максимум на срок его жизни, поэтому TTL держим коротким. Долгоживущая сессия
     * поддерживается через refresh-токен, который отзывается через БД.
     */
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.getAccessTokenTtlMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Проверяет подпись и срок действия токена и возвращает id пользователя из subject.
     * Бросает JwtException (или подкласс), если токен невалиден или истёк — вызывающий код
     * должен это отловить и просто не аутентифицировать запрос, а не падать с 500.
     */
    public UUID extractUserId(String token) throws JwtException {
        String subject = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return UUID.fromString(subject);
    }
}
