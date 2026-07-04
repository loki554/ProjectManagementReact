-- Порядковый номер задачи внутри проекта (#1, #2, ...), для отображения рядом с названием.
-- Счётчик хранится на projects (next_task_number), а не как MAX(task_number)+1: атомарный
-- UPDATE ... RETURNING на этом счётчике исключает гонку при одновременном создании задач,
-- в отличие от read-then-write через SELECT MAX (см. TaskService.nextPosition, где такая
-- гонка уже есть для position и осознанно не исправляется, т.к. коллизия там не критична).
ALTER TABLE projects ADD COLUMN next_task_number INT NOT NULL DEFAULT 1;

ALTER TABLE tasks ADD COLUMN task_number INT;

WITH numbered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY created_at, id) AS rn
    FROM tasks
)
UPDATE tasks t
SET task_number = numbered.rn
FROM numbered
WHERE t.id = numbered.id;

ALTER TABLE tasks ALTER COLUMN task_number SET NOT NULL;

ALTER TABLE tasks
    ADD CONSTRAINT uq_tasks_project_task_number UNIQUE (project_id, task_number);

UPDATE projects p
SET next_task_number = COALESCE((SELECT MAX(t.task_number) + 1 FROM tasks t WHERE t.project_id = p.id), 1);
