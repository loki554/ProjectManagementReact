package com.pmtracker.project_management_backend.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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
     * @param relativePath путь, ранее полученный из {@link StoredFile#relativePath()}
     */
    Resource load(String relativePath);

    void delete(String relativePath);
}
