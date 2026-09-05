package com.pmtracker.project_management_backend.mail;

import com.pmtracker.project_management_backend.config.JwtProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Токен для ссылки «отписаться» в письме (4.3).
 * <p>
 * Единственный токен приложения, который НЕ хранится в БД, — и это осознанное отличие от
 * схемы {@code SecureTokens} (случайное значение клиенту, SHA-256 в базу). Причина простая:
 * ссылка отписки стоит в каждом письме, то есть должна быть воспроизводима на момент
 * отправки, а не выдаваться один раз. Со случайным значением пришлось бы либо хранить его
 * сырым (то есть отказаться ровно от той защиты, ради которой хранят хеш), либо выписывать
 * новую строку на каждое письмо и чистить её потом.
 * <p>
 * Вместо этого токен детерминированно выводится из id получателя: {@code id.HMAC(id)}.
 * Подделать его без секрета нельзя, подобрать — тоже (128 бит подписи), а хранить нечего.
 * <p>
 * Ключ — секрет подписи JWT с собственной меткой назначения. Отдельной переменной окружения
 * здесь нет намеренно: она стала бы ещё одним обязательным секретом в проде ради функции,
 * цена компрометации которой — «кому-то перестали приходить письма, и он включил их обратно
 * в профиле». Метка нужна, чтобы значение, выведенное для отписки, нельзя было предъявить
 * куда-либо ещё, где используется тот же секрет. Плата — ротация {@code JWT_SECRET} гасит
 * ссылки в уже разосланных письмах; отписаться после этого можно из профиля.
 * <p>
 * Что этот токен НЕ даёт: ни входа в аккаунт, ни чтения чего-либо. Единственное действие,
 * которое им можно совершить, — выключить себе почтовые уведомления (см.
 * {@code NotificationController.unsubscribe}).
 */
@Component
public class UnsubscribeTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Метка назначения: с ней ключ отписки не совпадает ни с одним другим применением секрета. */
    private static final String KEY_PURPOSE = "pmtracker:unsubscribe:v1:";

    /** 128 бит подписи. Полные 256 удлиняют ссылку вдвое, не добавляя ничего к невозможности подбора. */
    private static final int SIGNATURE_BYTES = 16;

    private static final char SEPARATOR = '.';
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public UnsubscribeTokenService(JwtProperties jwtProperties) {
        // Секрет уже проверен на длину и на то, что это не dev-дефолт вне dev (см. JwtService).
        byte[] keyBytes = (KEY_PURPOSE + jwtProperties.getSecret()).getBytes(StandardCharsets.UTF_8);
        this.key = new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
    }

    public String tokenFor(UUID userId) {
        byte[] idBytes = toBytes(userId);
        return ENCODER.encodeToString(idBytes) + SEPARATOR + ENCODER.encodeToString(sign(idBytes));
    }

    /**
     * @return id получателя, если подпись сходится; {@code Optional.empty()} для любого мусора —
     *         битого Base64, чужой подписи, обрезанной строки. Вызывающий превращает пустой
     *         результат в INVALID_TOKEN, не различая эти случаи между собой.
     */
    public Optional<UUID> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int separator = token.indexOf(SEPARATOR);
        if (separator < 0) {
            return Optional.empty();
        }
        byte[] idBytes;
        byte[] signature;
        try {
            idBytes = DECODER.decode(token.substring(0, separator));
            signature = DECODER.decode(token.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (idBytes.length != Long.BYTES * 2) {
            return Optional.empty();
        }
        // isEqual, а не Arrays.equals: сравнение подписи не должно завершаться раньше на
        // первом несовпавшем байте — это ровно тот случай, ради которого метод и существует.
        if (!MessageDigest.isEqual(sign(idBytes), signature)) {
            return Optional.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(idBytes);
        return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
    }

    private byte[] sign(byte[] idBytes) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            byte[] full = mac.doFinal(idBytes);
            byte[] truncated = new byte[SIGNATURE_BYTES];
            System.arraycopy(full, 0, truncated, 0, SIGNATURE_BYTES);
            return truncated;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available in this JVM", e);
        }
    }

    private static byte[] toBytes(UUID userId) {
        return ByteBuffer.allocate(Long.BYTES * 2)
                .putLong(userId.getMostSignificantBits())
                .putLong(userId.getLeastSignificantBits())
                .array();
    }
}
