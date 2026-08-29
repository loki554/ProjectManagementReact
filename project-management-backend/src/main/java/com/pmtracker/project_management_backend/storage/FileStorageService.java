package com.pmtracker.project_management_backend.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Абстракция над файловым хранилищем. Сейчас единственная реализация — локальный диск
 * ({@link LocalFileStorageService}), но интерфейс намеренно не завязан на файловую систему,
 * чтобы позже можно было подменить на S3-совместимое хранилище, не трогая вызывающий код
 * (см. Decisions Log в IMPLEMENTATION_PLAN.md).
 */
public interface FileStorageService {

    /**
     * @param subdirectory логическая папка внутри хранилища, например "avatars/{userId}"
     * @return относительный путь (от корня хранилища) и размер сохранённого файла
     */
    StoredFile store(MultipartFile file, String subdirectory) throws IOException;

    /**
     * Сохранение уже готовых байт — для случаев, когда на диск ложится не то, что прислал клиент:
     * картинки проходят через {@link ImageSanitizer} и приезжают сюда перекодированными.
     *
     * @param extension    расширение с точкой (".png"), задаётся вызывающим кодом, а не клиентом
     * @param subdirectory логическая папка внутри хранилища, например "avatars/{userId}"
     */
    StoredFile store(byte[] content, String extension, String subdirectory) throws IOException;

    /**
     * @param relativePath путь, ранее полученный из {@link StoredFile#relativePath()}
     */
    Resource load(String relativePath);

    void delete(String relativePath);

    /**
     * Всё содержимое хранилища — для сверки с базой (3.6, OrphanedFileCleanupJob). Файл, на
     * который не ссылается ни одна строка, иначе остаётся на диске навсегда: при удалении
     * проекта (а до 3.5 — и задачи) строки attachments уходят по ON DELETE CASCADE, и до
     * файлов каскад не достаёт.
     *
     * <p>Возвращается список, а не поток: единственный вызывающий код всё равно строит из
     * него множество и сверяет с базой целиком. Для локального диска и объёмов, на которые
     * рассчитан этот трекер, это несколько тысяч строк раз в сутки.
     */
    List<StoredObject> listAll();
}
