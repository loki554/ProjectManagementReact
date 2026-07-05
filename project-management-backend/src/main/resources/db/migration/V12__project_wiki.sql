-- Вики проекта: одна markdown-страница на проект (UNIQUE по project_id).
-- Отдельная таблица, а не колонка в projects: не раздувает строку проекта,
-- хранит метаданные последнего изменения и расширяема (история версий и т.п.).
CREATE TABLE project_wiki (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL UNIQUE REFERENCES projects(id) ON DELETE CASCADE,
    content TEXT NOT NULL DEFAULT '',
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
