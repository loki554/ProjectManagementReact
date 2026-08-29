package com.pmtracker.project_management_backend.attachment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByTaskIdOrderByCreatedAtDesc(UUID taskId);

    /**
     * Пути всех файлов, на которые ссылается таблица — для сверки с диском (3.6,
     * OrphanedFileCleanupJob).
     *
     * <p>Нативный запрос сознательно: сверка решает, какие файлы удалить НАВСЕГДА, и
     * ошибиться в сторону «строки не видно» здесь дороже всего. Задача может лежать в
     * корзине (@SQLRestriction на Task, см. 3.5), и её вложения обязаны считаться
     * используемыми — иначе восстановленная задача вернулась бы без файлов.
     */
    @Query(value = "select stored_path from attachments", nativeQuery = true)
    List<String> findAllStoredPaths();
}
