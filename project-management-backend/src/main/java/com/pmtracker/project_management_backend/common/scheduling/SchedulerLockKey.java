package com.pmtracker.project_management_backend.common.scheduling;

/**
 * Ключи advisory-блокировок (3.8). Перечисление, а не число по месту вызова: пространство
 * advisory-блокировок в Postgres одно на всю базу и ничем не структурировано — два разных
 * задания, случайно выбравшие одно значение, будут молча мешать друг другу, и понять это по
 * поведению практически невозможно.
 *
 * <p>Сами числа произвольны. Требований к ним ровно два: не повторяться между собой и не
 * меняться со временем — иначе во время выката старые и новые инстансы возьмут разные
 * блокировки и оба сделают работу.
 */
public enum SchedulerLockKey {

    /** Сканирование дедлайнов и рассылка task_due_soon/task_overdue (NotificationScheduler). */
    NOTIFICATION_DUE_SCAN(7_710_001L);

    private final long value;

    SchedulerLockKey(long value) {
        this.value = value;
    }

    long value() {
        return value;
    }
}
