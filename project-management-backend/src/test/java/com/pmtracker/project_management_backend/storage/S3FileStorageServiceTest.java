package com.pmtracker.project_management_backend.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3-реализация хранилища (3.7) против настоящего MinIO в контейнере.
 * <p>
 * Мок SDK здесь бесполезен: он доказал бы, что позвали putObject с теми аргументами, которые
 * мы сами и передали. Интересно ровно обратное — как поведёт себя живой S3-совместимый
 * сервер: доедет ли содержимое, что вернёт листинг, работает ли presigned-ссылка из
 * браузера (то есть без единого заголовка авторизации).
 * <p>
 * Тест не поднимает Spring: проверяется одна реализация интерфейса, и контекст приложения ей
 * не нужен — только клиенты SDK, которые в проде собирает S3StorageConfig.
 */
class S3FileStorageServiceTest {

    private static final String BUCKET = "pmtracker-test";

    private static MinIOContainer minio;
    private static S3Client s3Client;
    private static S3Presigner s3Presigner;
    private static S3FileStorageService storage;

    @BeforeAll
    static void startMinio() {
        minio = new MinIOContainer("minio/minio");
        minio.start();

        S3StorageProperties properties = new S3StorageProperties(BUCKET, "us-east-1", minio.getS3URL(),
                minio.getUserName(), minio.getPassword(), true, true, Duration.ofMinutes(5));

        s3Client = S3Client.builder()
                .region(Region.of(properties.region()))
                .endpointOverride(URI.create(properties.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        s3Presigner = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .endpointOverride(URI.create(properties.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();

        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        storage = new S3FileStorageService(s3Client, s3Presigner, properties);
    }

    @AfterAll
    static void stopMinio() {
        if (minio != null) {
            minio.stop();
        }
    }

    @Test
    @DisplayName("загруженный файл читается обратно байт в байт")
    void storesAndLoadsContent() throws IOException {
        StoredFile stored = storage.store(multipart("note.txt", "содержимое вложения"), "tasks/42");

        assertThat(read(storage.load(stored.relativePath()))).isEqualTo("содержимое вложения");
    }

    /**
     * Ключ должен совпадать по форме с относительным путём локального хранилища: в БД лежит
     * одно и то же значение независимо от бэкенда, иначе переезд с диска в бакет превратился
     * бы в миграцию данных.
     */
    @Test
    @DisplayName("ключ объекта — это подкаталог, UUID и безопасное расширение")
    void buildsKeysLikeTheLocalStorageDoes() throws IOException {
        StoredFile stored = storage.store(multipart("note.txt", "x"), "tasks/42");

        assertThat(stored.relativePath())
                .matches("tasks/42/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.txt");
    }

    @Test
    @DisplayName("имя, присланное клиентом, в ключ не попадает")
    void ignoresTheClientFilename() throws IOException {
        StoredFile stored = storage.store(multipart("../../../etc/passwd.png", "x"), "avatars/1");

        assertThat(stored.relativePath()).doesNotContain("passwd").doesNotContain("..");
        assertThat(stored.relativePath()).endsWith(".png");
    }

    @Test
    @DisplayName("готовые байты сохраняются с расширением, заданным кодом")
    void storesRawBytes() throws IOException {
        StoredFile stored = storage.store("png bytes".getBytes(StandardCharsets.UTF_8), ".png", "projects/7");

        assertThat(stored.relativePath()).endsWith(".png");
        assertThat(read(storage.load(stored.relativePath()))).isEqualTo("png bytes");
    }

    @Test
    @DisplayName("удаление убирает объект, повторное удаление не падает")
    void deletesAnObject() throws IOException {
        StoredFile stored = storage.store(multipart("gone.txt", "x"), "tasks/42");

        storage.delete(stored.relativePath());
        storage.delete(stored.relativePath());

        assertThatThrownBy(() -> storage.load(stored.relativePath()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("отсутствующий объект — NoSuchElementException, как и у локального диска")
    void reportsAMissingObjectTheSameWay() {
        assertThatThrownBy(() -> storage.load("tasks/42/does-not-exist.txt"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("листинг возвращает загруженные объекты со временем записи")
    void listsStoredObjects() throws IOException {
        StoredFile stored = storage.store(multipart("listed.txt", "x"), "tasks/listing");

        List<StoredObject> objects = storage.listAll();

        assertThat(objects).anySatisfy(object -> {
            assertThat(object.relativePath()).isEqualTo(stored.relativePath());
            assertThat(object.lastModifiedAt()).isNotNull();
        });
    }

    /**
     * Смысл presigned-ссылки в том, что по ней ходят БЕЗ заголовков приложения — иначе она
     * не сняла бы с него нагрузку. Поэтому и проверяется голым HTTP-запросом.
     */
    @Test
    @DisplayName("presigned-ссылка отдаёт файл без единого заголовка авторизации")
    void presignedUrlServesTheFileAnonymously() throws IOException {
        StoredFile stored = storage.store(multipart("public.txt", "через ссылку"), "tasks/42");

        Optional<URI> url = storage.presignedUrl(stored.relativePath());

        assertThat(url).isPresent();
        assertThat(fetchAnonymously(url.get())).isEqualTo("через ссылку");
    }

    @Test
    @DisplayName("с выключенными presigned-ссылками их не выдают вовсе")
    void returnsNoUrlWhenPresigningIsOff() throws IOException {
        S3FileStorageService withoutPresigning = new S3FileStorageService(s3Client, s3Presigner,
                new S3StorageProperties(BUCKET, "us-east-1", minio.getS3URL(),
                        minio.getUserName(), minio.getPassword(), true, false, Duration.ofMinutes(5)));
        StoredFile stored = withoutPresigning.store(multipart("proxied.txt", "x"), "tasks/42");

        assertThat(withoutPresigning.presignedUrl(stored.relativePath())).isEmpty();
    }

    // ------------------------------------------------------------------------ хелперы

    private static MockMultipartFile multipart(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fetchAnonymously(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
