-- Личные "звёзды" проектов (как на GitHub): звезда — отметка конкретного пользователя,
-- счётчик — их сумма. Строка на пару (проект, пользователь), как в project_members.
CREATE TABLE project_stars (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, user_id)
);
