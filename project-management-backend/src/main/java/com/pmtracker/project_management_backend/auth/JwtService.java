package com.pmtracker.project_management_backend.auth;

import com.pmtracker.project_management_backend.config.JwtProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
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
