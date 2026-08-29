package com.pmtracker.project_management_backend.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки объектного хранилища (3.7). Читаются только при {@code app.storage.type=s3}.
 *
 * @param bucket             имя бакета; создание бакета не входит в задачи приложения
 * @param region             регион; для MinIO подходит любой валидный, подпись всё равно
 *                           считается локально
 * @param endpoint           адрес S3-совместимого сервера (MinIO локально). Пусто — значит
 *                           настоящий AWS S3, и адрес SDK соберёт сам по региону
 * @param accessKey          ключ доступа; пусто — учётные данные ищет стандартная цепочка
 *                           провайдеров AWS (переменные окружения, IAM-роль инстанса), что и
 *                           есть правильный способ в проде
 * @param secretKey          секрет к ключу выше
 * @param pathStyleAccess    адресация вида {@code endpoint/bucket/key} вместо
 *                           {@code bucket.endpoint/key}. Для MinIO обязательна: у него нет
 *                           wildcard-DNS под виртуальные хосты бакетов
 * @param presignedDownloads отдавать ли вложения редиректом на временную ссылку прямо в
 *                           хранилище вместо проксирования через приложение
 * @param presignedUrlTtl    срок жизни такой ссылки
 */
@ConfigurationProperties("app.storage.s3")
public record S3StorageProperties(
        String bucket,
        String region,
        String endpoint,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        boolean presignedDownloads,
        Duration presignedUrlTtl
) {
    public S3StorageProperties {
        region = region == null || region.isBlank() ? "us-east-1" : region;
        presignedUrlTtl = presignedUrlTtl == null ? Duration.ofMinutes(5) : presignedUrlTtl;
    }

    boolean hasStaticCredentials() {
        return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    boolean hasCustomEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
