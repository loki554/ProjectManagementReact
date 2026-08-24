package com.pmtracker.project_management_backend.storage;

/**
 * Результат перекодирования картинки в {@link ImageSanitizer}: только пиксели, без исходного
 * контейнера и метаданных.
 *
 * @param content     готовые байты для записи в хранилище
 * @param contentType MIME-тип получившегося файла (всегда image/png или image/jpeg)
 * @param extension   расширение с точкой, соответствующее contentType
 */
public record SanitizedImage(byte[] content, String contentType, String extension) {
}
