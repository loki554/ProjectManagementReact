-- Приглашение по email того, кто ещё не зарегистрирован (4.2 IMPROVEMENTS.md).
--
-- До этой таблицы пригласить можно было только существующего пользователя: invite искал
-- его в users и, не найдя, отдавал USER_NOT_FOUND_FOR_INVITE. Онбординг команды при этом
-- ломался на первом же шаге — коллегу приходилось сначала просить зарегистрироваться
-- самостоятельно, а потом добавлять вручную.
--
-- Строка здесь живёт ровно до принятия: accepted_at нет, принятое приглашение удаляется
-- (как password_reset_tokens после смены пароля). Кто и когда вошёл в проект, и так
-- записывается в project_activity событием member_added — второе место для того же факта
-- означало бы, что рано или поздно они разойдутся.

CREATE TABLE project_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    -- Хранится в нижнем регистре (нормализует ProjectInvitationService): приглашение
    -- выписывает один человек, а предъявляет другой, и «Ivan@example.com» в форме
    -- приглашения не должно расходиться с «ivan@example.com» при регистрации.
    email VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER')),
    -- Не сам токен, а его SHA-256 — как у password_reset_tokens и refresh_tokens и по той
    -- же причине: ссылка из письма даёт доступ к приватному проекту, и с сырыми значениями
    -- в БД одна утечка дампа отдала бы все непринятые приглашения сразу.
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    -- SET NULL, а не CASCADE: удаление пригласившего не должно отзывать уже отправленные
    -- приглашения — в письме указан проект, а не он лично.
    invited_by UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Одно живое приглашение на пару (проект, адрес). Повторное приглашение того же
    -- человека — это не вторая строка, а замена токена и срока у существующей: иначе
    -- «пригласить ещё раз» плодило бы рабочие ссылки, каждая из которых остаётся дверью
    -- в проект до самого истечения.
    CONSTRAINT uq_project_invitations_project_email UNIQUE (project_id, email)
);

-- Под поиск «какие приглашения выписаны на этот адрес» — он выполняется на каждом
-- подтверждении email (см. ProjectInvitationService.acceptAllPendingFor).
CREATE INDEX idx_project_invitations_email ON project_invitations(email);
