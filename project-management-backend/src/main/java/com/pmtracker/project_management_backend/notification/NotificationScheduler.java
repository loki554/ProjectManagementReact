package com.pmtracker.project_management_backend.notification;

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
 */
@Component
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private static final List<TaskStatus> INACTIVE_STATUSES = List.of(TaskStatus.DONE, TaskStatus.REJECTED);
    private static final Duration DUE_SOON_WINDOW = Duration.ofDays(3);
    private static final long FIXED_RATE_MS = 15 * 60 * 1000;
    private static final long INITIAL_DELAY_MS = 60 * 1000;

    private final TaskRepository taskRepository;
    private final NotificationService notificationService;

    public NotificationScheduler(TaskRepository taskRepository, NotificationService notificationService) {
        this.taskRepository = taskRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedRate = FIXED_RATE_MS, initialDelay = INITIAL_DELAY_MS)
    @Transactional
    public void checkDueDates() {
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
