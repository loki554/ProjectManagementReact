-- Восстановление пароля по ссылке из письма. Таблица сделана по образцу
-- email_verification_tokens (V1), но с двумя сознательными отличиями.
--
-- 1. Хранится не сам токен, а его SHA-256 — как у refresh_tokens, и по той же причине.
--    Токен сброса пароля это готовый захват аккаунта: с сырыми значениями в БД одна утечка
--    дампа отдаёт все аккаунты, у которых сброс сейчас в процессе. Токен верификации такой
--    силы не имеет, поэтому там UUID в открытом виде и остался.
-- 2. VARCHAR(255) вместо UUID: hex-представление SHA-256 — это строка, а не UUID.
--
-- Срок жизни (1 час против 24 у верификации) задаётся в коде, см. AuthService.

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
