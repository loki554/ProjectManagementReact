-- Категория задачи — свободный текст, который пользователь вводит вручную (необязательное
-- поле). В отличие от тэгов (см. V6) это не справочник со своим CRUD и цветом: значения не
-- нормализуются в отдельную таблицу, а подсказываются на фронтенде по уже использованным
-- в проекте категориям (см. TaskRepository.findDistinctCategories).
ALTER TABLE tasks
    ADD COLUMN category VARCHAR(100);

-- Под выборку различных категорий проекта для автодополнения.
CREATE INDEX idx_tasks_project_id_category ON tasks(project_id, category);
