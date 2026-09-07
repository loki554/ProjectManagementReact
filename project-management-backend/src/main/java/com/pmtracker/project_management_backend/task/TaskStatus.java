package com.pmtracker.project_management_backend.task;

import java.util.List;

public enum TaskStatus {
    NEW,
    IN_PROGRESS,
    PAUSED,
    FEEDBACK,
    DONE,
    REJECTED;

    /**
     * Статусы, в которых задача больше ничего не требует. По этому списку не шлются
     * напоминания о дедлайне (NotificationScheduler), не подсвечивается красным дата в
     * таблице (isTaskOverdue на фронте) и не попадают задачи в окна TaskDueFilter (4.7):
     * у выполненной задачи просроченный срок — не проблема, а история.
     */
    public static final List<TaskStatus> INACTIVE = List.of(DONE, REJECTED);
}
