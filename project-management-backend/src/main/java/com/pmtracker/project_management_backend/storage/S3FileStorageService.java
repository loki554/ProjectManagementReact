package com.pmtracker.project_management_backend.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище файлов в S3-совместимом объектном хранилище (3.7): MinIO локально, S3 или его
 * аналог в проде. Включается свойством {@code app.storage.type=s3} и в этом случае заменяет
 * собой {@link LocalFileStorageService}.
 *
 * <p>Зачем. Локальный диск живёт на конкретном инстансе: при двух инстансах вложение,
 * загруженное на первый, недоступно со второго, а в контейнере без внешнего тома оно
 * исчезает при первом же перезапуске. Абстракция {@link FileStorageService} была заведена
 * ровно под эту замену — вызывающий код не меняется ни в одном месте.
 *
 * <p>Ключ объекта совпадает с относительным путём локальной реализации ({@code
 * tasks/{id}/{uuid}.ext}), поэтому в БД лежит одно и то же значение независимо от бэкенда, а
 * переезд с диска в бакет — это копирование дерева, а не миграция данных.
 *
 * <p>Имя объекта строится по общему для обоих хранилищ правилу, см. {@link StorageKeys}.
 */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3FileStorageService implements FileStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3StorageProperties properties;

    public S3FileStorageService(S3Client s3Client, S3Presigner s3Presigner, S3StorageProperties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    @Override
    public StoredFile store(MultipartFile file, String subdirectory) throws IOException {
        String key = newKey(subdirectory, StorageKeys.extractSafeExtension(file.getOriginalFilename()));
        try (InputStream in = file.getInputStream()) {
            s3Client.putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(key).build(),
                    RequestBody.fromInputStream(in, file.getSize()));
        }
        return new StoredFile(key, file.getSize());
    }

    @Override
    public StoredFile store(byte[] content, String extension, String subdirectory) {
        String key = newKey(subdirectory, StorageKeys.sanitizeExtension(extension));
        s3Client.putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(key).build(),
                RequestBody.fromBytes(content));
        return new StoredFile(key, content.length);
    }

    /**
     * Поток из хранилища, завёрнутый в Resource. InputStreamResource, а не byte[]: вложение
     * может весить до 20 МБ, и держать его целиком в памяти на каждое скачивание незачем —
     * тем более что при {@code presigned-downloads} этот путь для вложений вообще не
     * используется.
     */
    @Override
    public Resource load(String relativePath) {
        try {
            ResponseInputStream<GetObjectResponse> object = s3Client.getObject(
                    GetObjectRequest.builder().bucket(properties.bucket()).key(relativePath).build());
            return new InputStreamResource(object);
        } catch (NoSuchKeyException e) {
            throw new NoSuchElementException("File not found: " + relativePath);
        }
    }

    @Override
    public void delete(String relativePath) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket()).key(relativePath).build());
        } catch (S3Exception e) {
            // best-effort, как и в локальной реализации: не удалённый старый файл не повод
            // ронять запрос, а сверка с базой (3.6) подберёт его позже.
        }
    }

    /**
     * Постраничный обход бакета: ListObjectsV2 отдаёт максимум 1000 ключей за раз, и
     * забыть про continuation token — значит получить сверку, которая молча считает
     * сиротами всё, что не поместилось на первую страницу, и удаляет живые файлы.
     */
    @Override
    public List<StoredObject> listAll() {
        List<StoredObject> objects = new ArrayList<>();
        s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(properties.bucket()).build())
                .contents()
                .forEach(object -> objects.add(new StoredObject(object.key(), object.lastModified())));
        return objects;
    }

    /**
     * Временная ссылка прямо в хранилище — чтобы скачивание вложения не шло байт за байтом
     * через приложение.
     *
     * <p>По умолчанию выключено, и это осознанно: браузер пойдёт по редиректу на чужой
     * домен, а значит бакету нужен CORS, разрешающий origin фронтенда. Проверить это из
     * приложения нечем, а неверная догадка превращает каждое скачивание в ошибку браузера
     * вместо медленного, но работающего ответа. Включать —
     * {@code app.storage.s3.presigned-downloads=true} после настройки CORS.
     */
    @Override
    public Optional<URI> presignedUrl(String relativePath) {
        if (!properties.presignedDownloads()) {
            return Optional.empty();
        }
        return Optional.of(s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(properties.presignedUrlTtl())
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(properties.bucket()).key(relativePath).build())
                        .build())
                .url()
                .toString())
                .map(URI::create);
    }

    private static String newKey(String subdirectory, String extension) {
        return subdirectory + "/" + UUID.randomUUID() + extension;
    }
}
