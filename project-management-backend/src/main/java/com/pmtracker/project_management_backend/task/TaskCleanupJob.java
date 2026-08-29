package com.pmtracker.project_management_backend.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Суточная чистка корзины: физическое удаление задач, пролежавших в ней дольше срока
 * хранения (3.5).
 * <p>
 * Без неё мягкое удаление превратилось бы в неудаление вовсе — строки, вложения и
 * залогированное время оставались бы в базе навсегда, просто невидимыми.
 * <p>
 * Как и TokenCleanupJob, джоб намеренно тупой и идемпотентный: никакого состояния, только
 * «удалить всё, что старше порога». Лишний запуск (второй инстанс приложения, перезапуск)
 * удалит ноль строк, поэтому распределённая блокировка ему не нужна.
 * <p>
 * Файлы вложений удалённых задач остаются на диске: строки уходят по ON DELETE CASCADE, а до
 * файлов каскад не достаёт. Этим занимается отдельная сверка диска с базой (3.6) — здесь
 * дублировать её логику незачем, а «удалить файлы по списку» посреди DELETE значило бы
 * потерять их при откате транзакции.
 */
@Component
public class TaskCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TaskCleanupJob.class);

    private final TaskRepository taskRepository;

    public TaskCleanupJob(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    // Расписание в свойстве по тем же причинам, что и у TokenCleanupJob: ночное окно по
    // умолчанию и возможность проверить джоб, не дожидаясь ночи. Время отличается от
    // 3:30 у токенов, чтобы две уборки не начинались одновременно.
    @Scheduled(cron = "${app.trash-cleanup.cron:0 45 3 * * *}")
    @Transactional
    public void purgeExpiredTrash() {
        Instant cutoff = Instant.now().minus(TaskService.TRASH_RETENTION);
        int purged = taskRepository.deleteTrashedBefore(cutoff);
        if (purged > 0) {
            log.info("Trash cleanup: permanently deleted {} task(s) trashed before {}", purged, cutoff);
        }
    }
}
