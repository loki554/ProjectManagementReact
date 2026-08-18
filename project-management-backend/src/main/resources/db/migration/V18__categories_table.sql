-- Категория задачи повышается из свободного текста (V17) в полноценную сущность проекта
-- со своей страницей управления — по образцу тэгов (V6), но без цвета: цвет остаётся
-- визуальным языком тэгов, категория рисуется нейтральным бейджем.
--
-- Свободный ввод в интерфейсе задачи при этом сохраняется: CategoryService.resolveOrCreate
-- заводит недостающую категорию на лету при сохранении задачи, поэтому пользователю
-- по-прежнему не нужно идти на страницу управления, чтобы указать новую категорию.

CREATE TABLE categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_categories_project_name UNIQUE (project_id, name)
);

CREATE INDEX idx_categories_project_id ON categories(project_id);

-- Переносим уже введённые значения: каждое различное tasks.category становится строкой
-- справочника. Автором записи назначаем владельца проекта — created_by в categories NOT NULL,
-- а кто именно из участников впервые ввёл это значение, в схеме V17 не сохранялось.
INSERT INTO categories (project_id, name, created_by)
SELECT DISTINCT t.project_id, t.category, p.created_by
FROM tasks t
JOIN projects p ON p.id = t.project_id
WHERE t.category IS NOT NULL;

ALTER TABLE tasks
    ADD COLUMN category_id UUID REFERENCES categories(id) ON DELETE SET NULL;

UPDATE tasks t
SET category_id = c.id
FROM categories c
WHERE c.project_id = t.project_id
  AND c.name = t.category;

DROP INDEX idx_tasks_project_id_category;

ALTER TABLE tasks
    DROP COLUMN category;

CREATE INDEX idx_tasks_category_id ON tasks(category_id);
