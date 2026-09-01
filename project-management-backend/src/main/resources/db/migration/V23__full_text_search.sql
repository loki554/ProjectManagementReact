-- Полнотекстовый поиск по задачам, комментариям и вики (4.1 IMPROVEMENTS.md).
--
-- До сих пор поиском был единственный `ilike '%…%'` по названию задачи внутри одного
-- проекта (3.3). Он не использует индекс (ведущий wildcard), ничего не знает о морфологии
-- («задачи» не находит «задача»), не ранжирует выдачу и не видит ни описаний, ни
-- комментариев, ни вики.
--
-- Берём штатный полнотекст Postgres: у каждой из трёх таблиц появляется генерируемая
-- колонка tsvector и GIN-индекс по ней. Генерируемая колонка, а не триггер и не пересчёт
-- в приложении: вектор считает БД, забыть его негде и разъехаться с собственной строкой он
-- не может — в том числе при UPDATE мимо ORM, которых в проекте хватает (см. V22).
--
-- Конфигурация 'russian', а не 'simple': в snowball-конфигурациях Postgres кириллица
-- (word/hword/hword_part) идёт через russian_stem, а латиница (asciiword/asciihword/
-- hword_asciipart) — через english_stem. То есть одна конфигурация покрывает оба языка,
-- на которых реально пишут в трекере. Имя конфигурации зашито в определение колонки, и
-- сменить его потом можно только новой миграцией — осознанная цена за то, что вектор
-- поддерживает БД, а не приложение.
--
-- Веса задают порядок выдачи через ts_rank (веса по умолчанию: A=1.0, B=0.4, C=0.2):
-- совпадение в названии задачи важнее, чем в её описании, а оно — важнее, чем в
-- комментарии или на странице вики.
--
-- Обрезать вход не нужно: длина всех трёх текстов ограничена на уровне DTO (title 255,
-- description 20000, тело комментария 2000, вики 100000 символов), а лимит одного
-- значения tsvector — 1 МБ.

ALTER TABLE tasks ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('russian', title), 'A') ||
    setweight(to_tsvector('russian', coalesce(description, '')), 'B')
) STORED;

ALTER TABLE task_comments ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('russian', body), 'C')
) STORED;

ALTER TABLE project_wiki ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('russian', content), 'C')
) STORED;

-- GIN, а не GiST: индекс строится один раз и читается на каждый поиск, а GIN как раз
-- быстрее на чтение и точнее (GiST даёт ложноположительные срабатывания и перепроверяет
-- их по таблице). Отдельного составного индекса с project_id нет: фильтр по проекту
-- закрывают уже существующие индексы, а склеить два битмапа Postgres умеет сам.
CREATE INDEX idx_tasks_search_vector ON tasks USING GIN (search_vector);
CREATE INDEX idx_task_comments_search_vector ON task_comments USING GIN (search_vector);
CREATE INDEX idx_project_wiki_search_vector ON project_wiki USING GIN (search_vector);
