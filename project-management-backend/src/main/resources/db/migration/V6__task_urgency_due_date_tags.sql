CREATE TABLE tags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    color VARCHAR(7) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tags_project_name UNIQUE (project_id, name)
);

CREATE INDEX idx_tags_project_id ON tags(project_id);

ALTER TABLE tasks
    ADD COLUMN urgency VARCHAR(20) NOT NULL DEFAULT 'MEDIUM'
        CHECK (urgency IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    ADD COLUMN due_date DATE,
    ADD COLUMN tag_id UUID REFERENCES tags(id) ON DELETE SET NULL;

CREATE INDEX idx_tasks_tag_id ON tasks(tag_id);
CREATE INDEX idx_tasks_assignee_id_status ON tasks(assignee_id, status);
