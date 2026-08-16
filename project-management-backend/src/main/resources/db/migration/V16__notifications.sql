-- Уведомления пользователя (колокольчик в хедере): назначение на задачу, комментарий
-- к задаче, приближающийся/просроченный дедлайн. task_id/actor_id — SET NULL, а не
-- CASCADE на actor_id (уведомление переживает удаление автора события, как и
-- project_activity.actor_id), но CASCADE на task_id: без задачи уведомление ("вам
-- назначили задачу X") теряет смысл и ссылку вести некуда — в отличие от ленты
-- активности, это не исторический журнал, а список актуальных дел.
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    type VARCHAR(32) NOT NULL,
    task_id UUID REFERENCES tasks(id) ON DELETE CASCADE,
    payload JSONB NOT NULL DEFAULT '{}',
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Список уведомлений пользователя, свежие сверху — единственный паттерн чтения ленты.
CREATE INDEX idx_notifications_recipient_created ON notifications (recipient_id, created_at DESC);

-- Счётчик непрочитанных в колокольчике опрашивается поллингом на каждой странице —
-- частичный индекс держит его дешёвым независимо от объёма прочитанной истории.
CREATE INDEX idx_notifications_recipient_unread ON notifications (recipient_id) WHERE read_at IS NULL;

-- Дедупликация task_due_soon/task_overdue в NotificationScheduler: "уже создавали такое
-- уведомление для этой задачи и получателя?" — без индекса это full scan на каждый тик.
CREATE INDEX idx_notifications_task_recipient_type ON notifications (task_id, recipient_id, type);
