package com.pmtracker.project_management_backend.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Секрет, который уходит пользователю, и его хеш, который остаётся у нас.
 * <p>
 * Схема одна и та же у refresh-токенов, ссылок сброса пароля (см. {@code AuthService}) и
 * приглашений в проект (см. {@code ProjectInvitationService}): клиенту отдаётся случайная
 * строка, в БД пишется только её SHA-256, так что утечка дампа не даёт готовых токенов.
 * Раз схема общая — общим должен быть и код: три копии генератора случайных значений и
 * хешера рано или поздно разъезжаются, и разъезжаются молча.
 * <p>
 * Не бин: состояния тут нет, а {@link SecureRandom} потокобезопасен и создаётся один раз.
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {
    }

    /** Случайное значение в Base64URL: numBytes байт из SecureRandom, без паддинга. */
    public static String generate(int numBytes) {
        byte[] randomBytes = new byte[numBytes];
        RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * SHA-256 в hex. Без соли и без замедления сознательно: это не пароль, а случайные
     * 256 бит — перебирать нечего, а быстрый хеш здесь нужен, потому что он считается
     * на каждой проверке токена.
     */
    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
