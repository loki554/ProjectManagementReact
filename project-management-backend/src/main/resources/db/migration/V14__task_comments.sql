CREATE TABLE task_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users(id),
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Один составной индекс обслуживает оба направления сортировки (newest/oldest):
-- btree читается в обе стороны.
CREATE INDEX idx_task_comments_task_created ON task_comments (task_id, created_at);
