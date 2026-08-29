package com.pmtracker.project_management_backend.storage;

/**
 * Общее для обеих реализаций хранилища правило именования: имя файла всегда генерирует
 * приложение, из присланного клиентом остаётся только расширение, да и то приведённое к
 * заведомо безопасному виду.
 *
 * <p>Правило одно на локальный диск и на S3 намеренно. Для диска это защита от path
 * traversal через originalFilename вида {@code ../../../etc/passwd.png}; в S3 обхода
 * каталогов нет, но есть ключи с {@code ../} и управляющими символами, а главное — общий
 * ключ давал бы возможность перезаписать чужой объект. Расходиться этим двум местам незачем.
 */
final class StorageKeys {

    private StorageKeys() {
    }

    /** Расширение из имени, присланного клиентом; пустая строка, если его нет или оно странное. */
    static String extractSafeExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        return dotIndex < 0 ? "" : sanitizeExtension(originalFilename.substring(dotIndex));
    }

    /**
     * Расширение, заданное кодом (например, {@link ImageSanitizer} после перекодирования),
     * прогоняется через ту же проверку: единственный способ гарантировать, что в имени не
     * окажется ничего лишнего, — не делать исключений.
     */
    static String sanitizeExtension(String extension) {
        String normalized = extension.toLowerCase();
        return normalized.matches("\\.[a-z0-9]{1,5}") ? normalized : "";
    }
}
