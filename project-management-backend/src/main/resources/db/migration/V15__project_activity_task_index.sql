-- Новый паттерн чтения: лента активности одной задачи (вкладка «Активность» на странице
-- просмотра задачи). Частичный индекс — project-level события (task_id IS NULL) в него
-- не попадают и место не занимают.
CREATE INDEX idx_project_activity_task_created
    ON project_activity (task_id, created_at DESC)
    WHERE task_id IS NOT NULL;
