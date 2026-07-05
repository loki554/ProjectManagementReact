-- Лента активности проекта. actor_id/task_id — SET NULL: событие переживает удаление
-- автора и задачи (payload хранит снапшот отображаемых строк на момент события).
-- payload jsonb: набор полей зависит от type, схема не фиксируется на уровне БД.
CREATE TABLE project_activity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    type VARCHAR(64) NOT NULL,
    task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Единственный паттерн чтения — постраничная лента одного проекта, свежие сверху.
CREATE INDEX idx_project_activity_project_created ON project_activity (project_id, created_at DESC);
