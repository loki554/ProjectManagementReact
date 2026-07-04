-- Человекочитаемый идентификатор проекта для URL (/projects/{slug}/...), генерируется один раз
-- при создании проекта (см. ProjectService.create) и больше не меняется — чтобы переименование
-- проекта не ломало уже сохранённые/переданные ссылки. Уникальность глобальная (не per-user),
-- т.к. slug используется напрямую в пути URL.
ALTER TABLE projects ADD COLUMN slug VARCHAR(255);

-- Бэкофилл существующих проектов той же нормализацией, что и ProjectService.slugify: lowercase,
-- всё, что не [a-z0-9], схлопывается в "-", затем обрезаются дефисы по краям. Дубликаты (два
-- проекта с одинаковым нормализованным именем) разруливаются добавлением "-2", "-3", ... по
-- порядку created_at — на момент миграции это единственный практичный способ сохранить
-- уникальность без ручного вмешательства.
WITH normalized AS (
    SELECT id,
           NULLIF(regexp_replace(lower(trim(both '-' from regexp_replace(name, '[^a-zA-Z0-9]+', '-', 'g'))), '^-+|-+$', '', 'g'), '') AS base_slug,
           created_at
    FROM projects
),
deduped AS (
    SELECT id,
           coalesce(base_slug, 'project') AS base_slug,
           row_number() OVER (PARTITION BY coalesce(base_slug, 'project') ORDER BY created_at, id) AS rn
    FROM normalized
)
UPDATE projects p
SET slug = CASE WHEN deduped.rn = 1 THEN deduped.base_slug ELSE deduped.base_slug || '-' || deduped.rn END
FROM deduped
WHERE p.id = deduped.id;

ALTER TABLE projects ALTER COLUMN slug SET NOT NULL;

ALTER TABLE projects ADD CONSTRAINT uq_projects_slug UNIQUE (slug);
