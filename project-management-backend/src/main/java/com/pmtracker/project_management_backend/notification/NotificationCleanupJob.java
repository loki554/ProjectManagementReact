package com.pmtracker.project_management_backend.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Суточная чистка давно прочитанных уведомлений (3.9).
 * <p>
 * Таблица notifications росла вечно: строка создаётся на каждое назначение, каждый
 * комментарий и каждый тик сканера дедлайнов, а удаляется только вместе с задачей или
 * пользователем. При этом прочитанное уведомление полугодовой давности не нужно никому —
 * ни списку (человек листает последние), ни счётчику непрочитанных, ни дедупликации.
 * <p>
 * Отдельного индекса под этот запрос нет намеренно: он обслуживал бы одно удаление в сутки,
 * а поддерживался бы на каждой записи уведомления. Ночной seq scan обходится дешевле.
 * <p>
 * Как и остальные уборки, джоб идемпотентен и не нуждается в блокировке между инстансами
 * (см. SchedulerLock о том, где она действительно нужна): второй запуск удалит ноль строк.
 */
@Component
public class NotificationCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationCleanupJob.class);

    /**
     * Сколько прочитанное уведомление ещё лежит в базе. Три месяца — это «хватит, чтобы
     * вернуться к письму после отпуска и найти, о чём была речь», и заметно меньше срока,
     * за который таблица успевает стать неудобной.
     */
    static final Duration READ_RETENTION = Duration.ofDays(90);

    private final NotificationRepository notificationRepository;

    public NotificationCleanupJob(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    // Своё окно: 3:30 — токены, 3:45 — корзина, 4:00 — файлы-сироты, 4:15 — уведомления.
    @Scheduled(cron = "${app.notifications.cleanup.cron:0 15 4 * * *}")
    @Transactional
    public void deleteOldReadNotifications() {
        Instant cutoff = Instant.now().minus(READ_RETENTION);
        int deleted = notificationRepository.deleteReadBefore(cutoff);
        if (deleted > 0) {
            log.info("Notification cleanup: deleted {} notification(s) read before {}", deleted, cutoff);
        }
    }
}
