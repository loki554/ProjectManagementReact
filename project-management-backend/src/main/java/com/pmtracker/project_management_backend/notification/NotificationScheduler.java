package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.common.scheduling.SchedulerLock;
import com.pmtracker.project_management_backend.common.scheduling.SchedulerLockKey;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Периодически сканирует активные назначенные задачи и создаёт task_due_soon/task_overdue
 * уведомления. Дедупликация ("уже уведомляли об этой задаче этого получателя этим типом?")
 * живёт в NotificationService, так что повторные тики безопасны — здесь нет собственного
 * состояния "кого уже проверяли". DUE_SOON_WINDOW совпадает с порогом "горящих" карточек на
 * фронтенде (ActiveTaskCard.DUE_SOON_THRESHOLD_MS) и с TaskService.URGENT_DUE_WINDOW —
 * единое определение "скоро истекает" на весь продукт, а не третье отдельное число.
 * <p>
 * Повторные тики безопасны, а вот ОДНОВРЕМЕННЫЕ — нет, и в этом разница между «сканировать
 * дважды» и «сканировать на двух инстансах» (3.8). Дедупликация в NotificationService
 * читает и пишет, то есть проигрывает гонке ровно тогда, когда два скана идут параллельно:
 * оба видят, что уведомления ещё нет, и оба его создают. Поэтому тик берёт
 * advisory-блокировку и просто пропускает работу, если её держит другой инстанс.
 */
@Component
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private static final List<TaskStatus> INACTIVE_STATUSES = List.of(TaskStatus.DONE, TaskStatus.REJECTED);
    private static final Duration DUE_SOON_WINDOW = Duration.ofDays(3);
    // Период и стартовая задержка сканирования вынесены в свойства с прежними значениями
    // по умолчанию (15 минут / 1 минута) — в конфигурации их никто не переопределяет, кроме
    // профиля test. Тестам нужен не другой период, а предсказуемость: они дёргают
    // checkDueDates() руками и проверяют, что именно он создал, а фоновый тик посреди
    // прогона добавлял бы уведомления, которых тест не просил (см. application-test.yml).
    private static final String FIXED_RATE_MS = "${app.notifications.due-scan.interval-ms:900000}";
    private static final String INITIAL_DELAY_MS = "${app.notifications.due-scan.initial-delay-ms:60000}";

    private final TaskRepository taskRepository;
    private final NotificationService notificationService;
    private final SchedulerLock schedulerLock;

    public NotificationScheduler(TaskRepository taskRepository,
                                 NotificationService notificationService,
                                 SchedulerLock schedulerLock) {
        this.taskRepository = taskRepository;
        this.notificationService = notificationService;
        this.schedulerLock = schedulerLock;
    }

    @Scheduled(fixedRateString = FIXED_RATE_MS, initialDelayString = INITIAL_DELAY_MS)
    @Transactional
    public void checkDueDates() {
        // Блокировка живёт до конца этой транзакции, то есть ровно столько, сколько идёт
        // скан, и снимается сама — в том числе если инстанс упадёт посреди работы.
        if (!schedulerLock.tryAcquire(SchedulerLockKey.NOTIFICATION_DUE_SCAN)) {
            log.debug("Due date scan skipped: another instance is running it");
            return;
        }

        Instant now = Instant.now();
        Instant cutoff = now.plus(DUE_SOON_WINDOW);
        List<Task> candidates = taskRepository.findActiveWithDueDateBefore(INACTIVE_STATUSES, cutoff);

        int created = 0;
        for (Task task : candidates) {
            boolean notified = task.getDueDate().isBefore(now)
                    ? notificationService.notifyOverdueIfNeeded(task)
                    : notificationService.notifyDueSoonIfNeeded(task);
            if (notified) {
                created++;
            }
        }
        if (created > 0) {
            log.info("Due date scan: {} new notification(s) out of {} candidate task(s)", created, candidates.size());
        }
    }
}
