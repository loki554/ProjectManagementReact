package com.pmtracker.project_management_backend.storage;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

import java.util.Locale;
import java.util.Map;

/**
 * Content-Type для отдачи сохранённой картинки (аватарка пользователя, превью проекта).
 *
 * Раньше тип определялся через {@code Files.probeContentType}, то есть зависел от таблицы MIME
 * операционной системы: на одной машине аватарка отдавалась как image/png, на другой — как
 * application/octet-stream, а сам вызов требовал, чтобы Resource лежал в файловой системе
 * (интерфейс {@link FileStorageService} этого не обещает — реализация может стать S3).
 *
 * Теперь расширение файла в хранилище генерирует не клиент, а {@link ImageSanitizer}: любая
 * картинка перекодируется в PNG или JPEG. Так что таблица ниже полная — остальные строки в ней
 * только ради файлов, загруженных до появления перекодирования.
 */
public final class StoredImageMediaType {

    private static final Map<String, MediaType> BY_EXTENSION = Map.of(
            "png", MediaType.IMAGE_PNG,
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "gif", MediaType.IMAGE_GIF,
            "webp", MediaType.parseMediaType("image/webp"));

    private StoredImageMediaType() {
    }

    /**
     * Неизвестное расширение — {@code application/octet-stream}: браузер такое не отрисует,
     * а вместе с {@code X-Content-Type-Options: nosniff} (SecurityConfig) и не попытается
     * угадать тип по содержимому.
     */
    public static MediaType of(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String extension = filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        return BY_EXTENSION.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM);
    }
}
